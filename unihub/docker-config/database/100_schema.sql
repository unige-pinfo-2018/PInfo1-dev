CREATE DATABASE UNIHUB_DB;
USE UNIHUB_DB;

DROP TABLE IF EXISTS USERS;

CREATE TABLE USERS (
  ID INT(11) NOT NULL AUTO_INCREMENT PRIMARY KEY,
  USERNAME VARCHAR(35) UNIQUE KEY,
  PASSWD VARCHAR(1000) NOT NULL,
  EMAIL VARCHAR(255) UNIQUE KEY NOT NULL,
  ROLE VARCHAR(50),
  CURSUS VARCHAR(255),
  CONFIRMED BOOLEAN
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE ACCOUNT_CONFIRMATIONS (
  ID INT(11) NOT NULL AUTO_INCREMENT PRIMARY KEY,
  USER_EMAIL VARCHAR(255) NOT NULL,
  CONFIRMATION_ID VARCHAR(50) NOT NULL,
  UNIQUE(USER_EMAIL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;