-- Create simple table schema for example:
-- products have product info
-- orders are have info about customer orders
-- orderproducts list what products and in what quantity were ordered
--
-- We don't care about customer info for this example

CREATE TABLE products (
  id serial primary key,
  name text,
  price numeric(6,2));

CREATE TABLE orders (
  id serial primary key,
  customer text,
  ordered_on timestamp,
  shipped_on timestamp);

CREATE TABLE orderproducts (
  order_id integer REFERENCES orders(id),
  product_id integer REFERENCES products(id),
  quantity integer
);

INSERT INTO products (name, price) VALUES
('Acme earthquake pills', 14.99),
('Fine leather jacket', 150),
('Log from Blammo!', 24.95),
('Illudium Q-36 explosive space modulator', 49.99),
('Blue pants', 70),
('Powerthirst!', 19.95),
('Tornado kit', 17.50),
('Boots of escaping', 999.95);

INSERT INTO orders (customer) VALUES ('foo@example.com');

INSERT INTO orderproducts (order_id,product_id,quantity) VALUES
(1, 1, 10),
(1, 4, 3),
(1, 8, 1);

SELECT * FROM pg_create_logical_replication_slot('ripley1', 'test_decoding');
