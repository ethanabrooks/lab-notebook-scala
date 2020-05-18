CREATE TABLE IF NOT EXISTS runs (
    checkpoint bytea,
    commitHash varchar NOT NULL,
    config varchar NOT NULL,
    configScript varchar,
    containerId varchar NOT NULL,
    description varchar NOT NULL,
    events bytea,
    killScript varchar NOT NULL,
    launchScript varchar NOT NULL,
    name varchar NOT NULL
);
