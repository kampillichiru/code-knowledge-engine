CREATE TABLE shipment (
    id        BIGINT         PRIMARY KEY,
    status    VARCHAR(10)    NOT NULL,
    quantity   DECIMAL(15, 2) NOT NULL,
    rate_pct  DECIMAL(5, 2)  NOT NULL,
    due_date  DATE           NOT NULL
);
