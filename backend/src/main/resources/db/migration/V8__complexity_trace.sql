-- How the question of a run scored when its model was chosen: the score, and the rules it met as a comma separated
-- list of their names (see ComplexityRule). Both are null for a run that was not scored, because no larger model was
-- configured or because it was answered before the score was kept.
ALTER TABLE chat_runs
    ADD COLUMN complexity_score INT          NULL,
    ADD COLUMN complexity_rules VARCHAR(255) NULL;
