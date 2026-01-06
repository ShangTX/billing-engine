CREATE TABLE billing_unit_record
(
    id            BIGINT PRIMARY KEY,
    parking_id    BIGINT         NOT NULL,
    order_id      BIGINT         NOT NULL,

    begin_time    DATETIME       NOT NULL,
    end_time      DATETIME       NOT NULL,

    charge_amount DECIMAL(10, 2) NOT NULL,
    free_amount   DECIMAL(10, 2) NOT NULL,

    scheme_id     BIGINT         NOT NULL,
    rule_id       BIGINT         NOT NULL,
    rule_version  INT            NOT NULL,

    is_free       tinyint(1)        NOT NULL,

    INDEX         idx_time (unit_start_time, unit_end_time),
    INDEX         idx_order (order_id),
    INDEX         idx_parking_time (parking_id, unit_start_time)
);
