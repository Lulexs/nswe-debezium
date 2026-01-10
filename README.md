# DEBEZIUM

## CDC - Change Data Capture

Change Data Capture je tehnika koja omogućava praćenje promena nad podacima u bazi podataka. Postoje različiti načini na koji se ova tehnika može implementirati, što u mnogome zavisi i od baze podataka koja se koristi, ali se pristupi najčešće baziraju na objavljivanju promena nad podacima u okviru nekog sistema za razmenu poruka (najčešće Kafka). Objavljene poruke se čitaju od strane drugog sistema koji te podatke može da upisuje u neku drugu bazu podataka, keš itd. Tipične situacije u kojima se koristi CDC tehnika su:

1. Potrebno je neki podatak upisati/ažurirati u veći baza podataka. U tom slučaju umesto da se implementira obrazac za upravljanje distribuiranim transakcijama (Saga pattern, Dvofazni commit) moguće je upisati podatak u jednu bazu, a nakon emitovanja promena iz te baze replicirati u ostalim bazama. Ovakav pristup podrazumeva da je _eventual consistency_ prihvatljiv u sistemu.
2. Održavanje keš konzistencije. Funkcioniše po sličnom principu kao prethodna stavka, promene iz glavne baze se objavljuju i po potrebi vrši se invalidiranje/ažuriranje stavki u kešu.
3. CQRS - Implementacija CQRS projektnog obrasca, gde su u glavnoj bazi podataka podaci smešteni tako da je upis veoma brz, a zatim se ta promena propagira do druge baze podataka koja je optimizovana za čitanje.

## Šta je Debezium

Debezium je platforma koja se koristi za implementaciju CDC-a. Za objavljivanje poruka koristi se Apache Kafka (moguće je koristiti i neki drugi sistem korišćenjem Debezium servera, ali nije primarni način na koji se koristi Debezium). Debezium se oslanja na KafkaConnect (platforma u okviru Apache Kafke koja se koristi za strimovanje podataka, bez potrebe da se piše custom code). Glavna komponenta Debeziuma je tzv. Debezium source connector. Radi se o komponenti koja se povezuje na željeni izvor, nakon čega počinje da prati promene, a te promene objavljuje na Kafku. Postoji podrška za PostgreSQL, Oracle, MySQL, MariaDB, SQL Server, Db2, Cassandra, Cassandra... Debezium takođe nudi i Debezium sink connetor, komponenta koja ima mogućnost da se poveže na Kafku i da promene iz glavne baze "sipa" u neki drugi sistem (warehouse baza, keš, elastic...). Na sledećoj slici prikazana je Debezium arhitektura.
![Debezium architecture](https://debezium.io/documentation/reference/stable/_images/debezium-architecture.png)

## Kako Debezium implementira CDC

Jedan od mogućih načina na koji se CDC implementira je korišćenjem trigera. Ovaj pristup ima puno nedostataka - trigeri mogu da silent fail-uju, što znači da nije moguće detektovati promenu. Pored toga postoje poteškoće kako promene koje se detektuju kroz triger objaviti van baze podataka.

Debezium za razliku od pristupa koji koristi trigere oslanja na log fajlove. U različitim bazama podataka ovi fajlovi se zovi različito, bin log kod MySQL-a, write-ahead-log kod PostgreSQL-a, redo log kod Oracle-a, ali bez obzira na ime imaju sličnu funkciju - obezbeđivanje durability osobine baza podataka. Podaci koji se upisuju u bazu se pored tabele upisuju i u odgovarajući log fajl. Debezium promene čita direktno iz log fajlova!

## Korišćenje Debezium-a za CDC nad PostgreSQL-om

Kako bi Debezium mogao da prati promene nad podacima u PostgreSQL-u, potrebno je kreirati user-a sa neophodnim rolama u okviru Postgre-a. Od rola Debezium zahteva LOGIN i REPLICATION. Postgre, od verzije 9.4 nudi mehanizam koji dosta pojednostavljuje način na koji se transakcije čitaju iz WAL-a, pa Debezium koristi taj mehanizam. Mehanizam se sastoji u tome da Debezium ostvaruje konekciju sa Postgre-om, nakon čega se komunikacija prebacuje u streaming replication protocol mod, kojim Postgre sam strimuje podatke u odgovarajućem formatu klijentu. Debezium konektor vrši reformatiranje tih podataka i objavljuje ih na kafku.

### 1. Snapshotting

Obzirom da se WAL periodično prazni, Debezium nudi mogućnost da se nakon uspostavljanja konekcije po prvi put napravi snapshot tabela čije promene se prate. Pokreće se read only transakcija, čitaju se svi trenutni podaci iz tabela i šalju se na Kafku. Pored ovog načina, Debezium ima podršku i za Ad hoc snapshot-ove. Ovaj mod omogućava da se tokom strimovanja podataka ponovi snapshot, i pogodno je da se koristi u situacijama kada se promenio set tabela koji se prati.

Debezium ima podršku i za tzv. inkrementalne snapshot-ove. Ova funkcionalnost omogućava da, umesto da se čeka pravljenje kompletnog snapshot-a, omogućava da se snapshot pravi inkrementalno. Podaci u tabeli se sortiraju po vrednosti primarnog ključa, a zatim se redovi u chunkovima određene veličine šalju na Kafku, pri čemu Debezium paralelno radi u streaming modu.

### 2. Streaming

Najveći deo vremena Debezium provodi u streaming modu, kada emituje promene nad podacima u bazi podataka. Kada se Debezium koristi u kombinaciji sa PostgreSQL-om, postoji mogućnost da se strimuju i poruke koje se odnose na transakcije, koje sadrže informacije o tome kada je transakcija započela, kada je završila, koje tabele su izmenjene i slično. Ipak, Debezium se najčešće koristi za praćenje promena nad samim podacima.

Važno je napomenuti kako se formira ime Kafka topic-a na koji se šalju promene iz tabela. Podrazumevano, ime topic-a se formira tako što se spoje ime baze podataka, ime šeme i ime tabele korišćenjem tačke - &lt;baza&gt;&lt;sema&gt;&lt;tabela&gt;. Ovo znači da je podrazumevano ponašanje da postoji 1 Kafka topic za svaku tabelu čije promene se prate. Debezium nudi podršku za custom rutiranje, što se podešava kroz KafkaConnect podešavanja. U nastavku je prikazan primer kako je moguće da se sve promene šalju na samo 1 Kafka topic.

<pre>
    "transforms": "Reroute",
    "transforms.Reroute.topic.regex": "dbz\\.public\\..*",
    "transforms.Reroute.topic.replacement": "CDC_1",
    "transforms.Reroute.type": "io.debezium.transforms.ByLogicalTableRouter"
</pre>

#### Kafka events

Sam event koji se emituje na Kafku može da se emituje u različitim formatima. Za realnu primenu format korišćenje formata kao što je Avro sa Schema registry-jem nudi najveću fleksibilnost i optimizaciju. Za demonstraciju zbog čitljivosti korišćen je JSON format. Sam event koji se emituje na Kafku je JSON objekat koji se sastoji od slecećih polja:

1. before - vrednost koju je red imao pre (null, ako je operacija insert)
2. after - nova vrednost reda (null, ako je operacija delete)
3. source - izvor odakle dolazi event
4. op - operacija

- c - create
- u - update
- d - delete
- r - read (u snapshot modu)
- t - truncate
- m - message (postgres periodično u WAL upisuje stvari koje se ne odnose na podatke u bazi, message op se odnosi na ovakvu aktivnost nad WAL-om)

5. ostali meta podaci, kao što je id transakcije koja je izvršila promenu, vreme izvršenja i slično.

#### before i after

Politika Debeziuma je da svaki red bude self-sufficient, odnosno da svaki event bude dovoljan za obradu bez potrebe za poznavanjem načina na koji je on proistekao iz izvora i na koji način su podaci tamo smešteni. Kako bi to bilo moguće, podrazumevano ponašanje Debeziuma je da svaki emitovani event sadrži i deo koji opisuje tipove podataka u samom eventu. Alternativno moguće je koristiti neku vrstu schema registry-ja. Obzirom da Debezium garantuje jedinstvenost evenat-a na osnovu primarnog ključa, obavezan je i deo koji opisuje tipove unutar primarnog ključa (koji može biti kompozitni). U nastavku je prikazan primer insert event-a emitovanog na Kafku koji sadrži opis šema:

```json
{
  "schema": {
    "type": "struct",
    "fields": [
      {
        "type": "struct",
        "fields": [
          {
            "type": "int32",
            "optional": false,
            "field": "user_id"
          },
          {
            "type": "string",
            "optional": false,
            "field": "attribute_name"
          },
          {
            "type": "string",
            "optional": true,
            "field": "attribute_value"
          }
        ],
        "optional": true,
        "name": "CDC_1.Value",
        "field": "before"
      },
      {
        "type": "struct",
        "fields": [
          {
            "type": "int32",
            "optional": false,
            "field": "user_id"
          },
          {
            "type": "string",
            "optional": false,
            "field": "attribute_name"
          },
          {
            "type": "string",
            "optional": true,
            "field": "attribute_value"
          }
        ],
        "optional": true,
        "name": "CDC_1.Value",
        "field": "after"
      },
      {
        "type": "struct",
        "fields": [
          {
            "type": "string",
            "optional": false,
            "field": "version"
          },
          {
            "type": "string",
            "optional": false,
            "field": "connector"
          },
          {
            "type": "string",
            "optional": false,
            "field": "name"
          },
          {
            "type": "int64",
            "optional": false,
            "field": "ts_ms"
          },
          {
            "type": "string",
            "optional": true,
            "name": "io.debezium.data.Enum",
            "version": 1,
            "parameters": {
              "allowed": "true,last,false,incremental"
            },
            "default": "false",
            "field": "snapshot"
          },
          {
            "type": "string",
            "optional": false,
            "field": "db"
          },
          {
            "type": "string",
            "optional": true,
            "field": "sequence"
          },
          {
            "type": "int64",
            "optional": true,
            "field": "ts_us"
          },
          {
            "type": "int64",
            "optional": true,
            "field": "ts_ns"
          },
          {
            "type": "string",
            "optional": false,
            "field": "schema"
          },
          {
            "type": "string",
            "optional": false,
            "field": "table"
          },
          {
            "type": "int64",
            "optional": true,
            "field": "txId"
          },
          {
            "type": "int64",
            "optional": true,
            "field": "lsn"
          },
          {
            "type": "int64",
            "optional": true,
            "field": "xmin"
          }
        ],
        "optional": false,
        "name": "io.debezium.connector.postgresql.Source",
        "field": "source"
      },
      {
        "type": "struct",
        "fields": [
          {
            "type": "string",
            "optional": false,
            "field": "id"
          },
          {
            "type": "int64",
            "optional": false,
            "field": "total_order"
          },
          {
            "type": "int64",
            "optional": false,
            "field": "data_collection_order"
          }
        ],
        "optional": true,
        "name": "event.block",
        "version": 1,
        "field": "transaction"
      },
      {
        "type": "string",
        "optional": false,
        "field": "op"
      },
      {
        "type": "int64",
        "optional": true,
        "field": "ts_ms"
      },
      {
        "type": "int64",
        "optional": true,
        "field": "ts_us"
      },
      {
        "type": "int64",
        "optional": true,
        "field": "ts_ns"
      }
    ],
    "optional": false,
    "name": "CDC_1.Envelope",
    "version": 2
  },
  "payload": {
    "before": null,
    "after": {
      "user_id": 1,
      "attribute_name": "height",
      "attribute_value": "182"
    },
    "source": {
      "version": "2.7.3.Final",
      "connector": "postgresql",
      "name": "dbz",
      "ts_ms": 1767894226670,
      "snapshot": "false",
      "db": "postgres",
      "sequence": "[\"25078432\",\"25101208\"]",
      "ts_us": 1767894226670374,
      "ts_ns": 1767894226670374000,
      "schema": "public",
      "table": "user_attributes",
      "txId": 768,
      "lsn": 25101208,
      "xmin": null
    },
    "transaction": null,
    "op": "c",
    "ts_ms": 1767894227178,
    "ts_us": 1767894227178313,
    "ts_ns": 1767894227178313289
  }
}
```

U nastavku su prikazani create, update i delete eventi koji se emituju na Kafku i ne sadrže schema polja.

1. Insert

```json
{
  "before": null,
  "after": {
    "user_id": 1,
    "attribute_name": "height",
    "attribute_value": "182"
  },
  "source": {
    "version": "2.7.3.Final",
    "connector": "postgresql",
    "name": "dbz",
    "ts_ms": 1767813361449,
    "snapshot": "false",
    "db": "postgres",
    "sequence": "[\"25185064\",\"25185120\"]",
    "ts_us": 1767813361449509,
    "ts_ns": 1767813361449509000,
    "schema": "public",
    "table": "user_attributes",
    "txId": 770,
    "lsn": 25185120,
    "xmin": null
  },
  "transaction": null,
  "op": "c",
  "ts_ms": 1767813361911,
  "ts_us": 1767813361911197,
  "ts_ns": 1767813361911197727
}
```

2. Update

```json
{
  "before": {
    "user_id": 1,
    "attribute_name": "height",
    "attribute_value": "182"
  },
  "after": {
    "user_id": 1,
    "attribute_name": "height",
    "attribute_value": "183"
  },
  "source": {
    "version": "2.7.3.Final",
    "connector": "postgresql",
    "name": "dbz",
    "ts_ms": 1767813524088,
    "snapshot": "false",
    "db": "postgres",
    "sequence": "[\"25185520\",\"25186272\"]",
    "ts_us": 1767813524088716,
    "ts_ns": 1767813524088716000,
    "schema": "public",
    "table": "user_attributes",
    "txId": 771,
    "lsn": 25186272,
    "xmin": null
  },
  "transaction": null,
  "op": "u",
  "ts_ms": 1767813524338,
  "ts_us": 1767813524338060,
  "ts_ns": 1767813524338060199
}
```

3. Delete

```json
{
  "before": {
    "user_id": 1,
    "attribute_name": "height",
    "attribute_value": "183"
  },
  "after": null,
  "source": {
    "version": "2.7.3.Final",
    "connector": "postgresql",
    "name": "dbz",
    "ts_ms": 1767813555180,
    "snapshot": "false",
    "db": "postgres",
    "sequence": "[\"25186424\",\"25186480\"]",
    "ts_us": 1767813555180271,
    "ts_ns": 1767813555180271000,
    "schema": "public",
    "table": "user_attributes",
    "txId": 772,
    "lsn": 25186480,
    "xmin": null
  },
  "transaction": null,
  "op": "d",
  "ts_ms": 1767813555297,
  "ts_us": 1767813555297637,
  "ts_ns": 1767813555297637410
}
```

#### Datatypes

Debezium ima podršku za praktično sve popularne relacione baze i neke NoSQL baze. Postoji razlika u načinu na koji različite baze pamte iste tipove podataka. Kako bi Debezium prevazišao ovaj problem, prilikom slanja eventa na Kafku, Debezium konvertuje podatke u custom tipove koji se kasnije konvertuju u odgovarajući tip u zavisnosti od potrebe.

#### Default values

Ukoliko neka kolona poseduje default vrednost i na osnovu toga se izostavi prilikom upisa podatka u tabelu, Debezium će pokušati da pribavi ovu vrednost kako bi je propagirao. Ovo je moguće da se uradi za većinu najčešće korišćenih tipova podataka. Ukoliko se vrednost kolone izračunava kao rezultat izvršenja neke funkcije, Debezium će u eventu emitovati default vrednost tog tipa podatka.

## Debezium Sink Connectors

Debezium Sink Connectori su zapravo KafkaConnect sink konektori koji konzumiraju evente sa Kafka topica i upisuju te podatke u neki drugi sistem. Trenutno postoje sink konektori za bilo koju relacionu bazu podataka za koju postoji JDBC draver ili MongoDB. Potrebno je naglasiti da ukoliko se koristi bilo koji sink konektor koji ne potiče od Debeziuma, potrebno je proširiti takav konektor da transformiše evente, u suprotnom, ako se koriste Debezium sink konektori nije potrebno vršiti ovakve transformacije.

Debezium JDBC sink konektori nude:

1. At-least-once delivery - svaki event koji se pričita sa Kafke, biće i obrađen
2. Automatsko mapiranje tipova podataka i tipova kolona
3. Idempotentni upisi - nije podrazumevano podešavanje, moguće je podesiti insert.mode atribut konektora
4. Schema evolution - osnovna podrška za detektovanje promena u šemi izvorne tabele

Prilikom korišćenja Debezium JDBC konektora, potrebno je da u odredišnoj bazi već postoje odgovarajuće tabele u koje će da se presipaju podaci iz glavne tabele. Određivanje imena tabele u koju se upisuju podaci vrši se na osnovu imena Kafka topic-a sa kog je event konzumiran. Postoji podrška za definisanje custom mapiranja implementacijom io.debezium.sink.naming.CollectionNamingStrategy interface-a.

Važno je napomenuti, generalno prilikom korišćenja Kafke, da Kafka garantuje da će se poruke čitati onim redom koji stižu samo u okviru jedne particije u okviru topic-a. Topic obično sadrži veći broj particija, tako da ne postoji garancija da će se poruke iz različitih particija čitati u nekom redosledu. Ovo može dovesti do toga da je u nekim situacijama neophodno relaksirati ograničenja koja postoje u odredišnoj bazi, bez obzira da li se koristi Debezium JDBC sink ili se implementira custom sink.

## Uputstvo za pokretanje primera

Primer se sastoji iz docker compose fajla kojim se pokreću sve neophodne tehnologije kako bi se demonstrirala funkcionalnost debeziuma. Pokreću se sledeće stvari:

- PostgreSQL - služi kao glavna baza podataka nad kojom se primenjuje CDC, tj. na koju se kači Debezium source connector korišćenjem KafkaConnect-a
- Oracle - koristi se kao baza u koju se repliciraju podaci iz Postgre baze korišćenjem Debezium JDBC sink connector-a
- Apache Kafka - pokreće se jedan Kafka broker korišćenjem KRaft protokola
- KafkaUI - web ui alat kojim može da se posmatraju poruke koje su objavljene na neki kafka topic
- DebeziumConnect - custom Debezium build KafkaConnect slike koji dolazi sa svim source i sink driverima

Pored toga u repozitorijumu se nalazi i postgres.sql fajl u kome se nalaze odgovarajuće DDL naredbe kojima se kreiraju odgovarajuće tabele, kao i odgovarajuće SQL naredbe kojima se kreiraju odgovarajuće role i drugi neophodni objekti kako bi Debezium mogao da radi nesmetano. Postoji i oracle.sql fajl u kome se nalaze ekvivalentne DDL naredbe za kreiranje tabela u Oracle bazi.

Nakon pokretanja svih neophodnih stvari, potrebno je inicijalizovati source i sink konektore u ovkiru KafkaConnect-a. To se može izvršiti na sledeći način, korišćenjem **postgres-connector.json** i **oracle-sink.sql** fajlova koji su dostupni u repozitorijumu.

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  --data @postgres-connector.json \
  http://localhost:8083/connectors
```

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  --data @oracle-sink.json \
  http://localhost:8083/connectors
```

Projekat sadrzi i custom redis-sink. Radi se o projektu koji je realizovan koriscenjem programskog jezika Java i SpringBoot framework-a. Uloga ovog servisa je da ažurira podatke u Redisu, koji se najčešće koristi za keširanje. Cilj ovog servisa je demonstracija implementacije custom sink connector-a. Ovaj projekat se povezuje na Kafku i sa odgovarajućih topic-a čita cdc event-e. U zavisnosti od tipa eventa upisuje ili briše podatak iz Redis-a.

Na narednoj slici je prikazan blok dijagram sistema koji se dobije pokretanjem docker compose-a i redis sink-a.
![Screenshot](diagram.png)
