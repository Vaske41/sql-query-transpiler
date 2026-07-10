# SQL Query Transpiler

Source-to-source SQL translator between **T-SQL (SQL Server)**, **MySQL**, and
**PostgreSQL** — 6 translation directions over core DML and DDL. Master's thesis
project (ETF, University of Belgrade), built with Java 17 and ANTLR4 using an
Apache Spark Catalyst-inspired rule-based pipeline.

## Build

    ./mvnw clean verify   # Linux/macOS
    .\mvnw.cmd clean verify  # Windows

Requires JDK 17+. Maven is provided by the committed wrapper.
