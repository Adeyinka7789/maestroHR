-- Add loan_deduction column to final_settlements (outstanding loan balance owed at exit)
ALTER TABLE final_settlements
    ADD COLUMN IF NOT EXISTS loan_deduction DECIMAL(15,2) DEFAULT 0;
