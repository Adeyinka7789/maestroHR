ALTER TABLE employees
    ALTER COLUMN date_of_birth DROP NOT NULL,
    ALTER COLUMN gender        DROP NOT NULL,
    ALTER COLUMN marital_status DROP NOT NULL,
    ALTER COLUMN address       DROP NOT NULL;
