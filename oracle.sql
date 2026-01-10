CREATE TABLE app_user (
    id NUMBER PRIMARY KEY,
    first_name VARCHAR2(50) NOT NULL,
    last_name  VARCHAR2(50) NOT NULL,
    birthday   DATE NOT NULL
);

CREATE TABLE user_attributes (
    user_id NUMBER NOT NULL,
    attribute_name  VARCHAR2(50) NOT NULL,
    attribute_value VARCHAR2(50),
    CONSTRAINT pk_user_attributes PRIMARY KEY (user_id, attribute_name)
);
