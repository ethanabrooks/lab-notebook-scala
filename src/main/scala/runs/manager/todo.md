- reproduce
- relaunch
- load
- scrape events and checkpoints
- use metaprogramming to consolidate field names
- use enum instead of string for fields
- use doobie
- use fs2-io
```sql
drop table customer;
CREATE TABLE CUSTOMER (id number, name varchar(20), age number, address varchar(20), 
salary number);  

INSERT into CUSTOMER values (1, 'Ramesh', 32, 'Ahmedabad', 2000); 
INSERT into CUSTOMER values (2, 'Khilan', 25, 'Delhi', 1500); 
  
SELECT * FROM Customer;
set autocommit off;
INSERT into CUSTOMER values (3, 'kaushik', 23, 'Kota', 2000); 
PREPARE COMMIT mycommit;
SELECT * FROM INFORMATION_SCHEMA.IN_DOUBT;
SELECT * FROM Customer;
ROLLBACK TRANSACTION mycommit;
SELECT * FROM Customer;
```
```sql
set autocommit off;
DROP TABLE TEST;
CREATE TABLE TEST(ID NUMERIC(15) NOT NULL);
insert into test values (1);
savepoint yay;
select * from test;
insert into test values (2);
rollback to savepoint yay;
select * from test;
```