-- Each site has a housekeeping unit manager and/or a housekeeping supervisor.
ALTER TABLE site ADD COLUMN supervisor TEXT;
ALTER TABLE audit ADD COLUMN supervisor TEXT;
