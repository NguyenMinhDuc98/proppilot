-- The seed gives every lease the same end date, 13 months out, so "which leases end soon" would have no answer.
-- Seven leases now end 14 to 56 days after the migration date, spread out. Deterministic and relative to the migration
-- date like the rest of the seed; only leases.end_date changes.
--
-- Leases are chosen by their position among the active leases ordered by id, because that is how the lease corpus picks
-- the leases that get a document (the first 30 by default). Five positions are inside the first 30, so a question about
-- an expiring lease can be answered from a document; two are outside, so the same question also finds leases that have
-- no document. Positions 6, 11, 17, 22 and 28 are spread over the first 30 and keep clear of 29, the lease whose
-- document carries the planted instruction. Positions 40 and 75 are well outside the selection.
UPDATE leases l
SET end_date = CURRENT_DATE + e.days
FROM (SELECT id, row_number() OVER (ORDER BY id) AS position FROM leases WHERE active) a
JOIN (VALUES (6, 27), (11, 14), (17, 49), (22, 38), (28, 56), (40, 21), (75, 33)) AS e (position, days)
    ON e.position = a.position
WHERE l.id = a.id;
