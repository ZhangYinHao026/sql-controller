CREATE DATABASE IF NOT EXISTS `sql-controller` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `sql-controller`;

SOURCE src/main/resources/schema.sql;
