CREATE TABLE orders (
    id UUID PRIMARY KEY,
    customer_email VARCHAR(255) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
