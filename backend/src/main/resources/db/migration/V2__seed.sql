-- Fake seed data. Deterministic (no random()) and relative to the migration date,
-- so the demo always shows 12 months of history ending "now".

INSERT INTO cities (name_en, name_ar) VALUES
    ('Riyadh', 'الرياض'),
    ('Jeddah', 'جدة'),
    ('Dammam', 'الدمام');

INSERT INTO buildings (code, name, city_id, address)
SELECT b.code, b.name, c.id, b.address
FROM (VALUES
    ('A', 'Palm Residence',   'Riyadh', 'Olaya District, Riyadh'),
    ('B', 'Najd Towers',      'Riyadh', 'Al Malqa District, Riyadh'),
    ('C', 'Corniche Heights', 'Jeddah', 'Al Shati District, Jeddah'),
    ('D', 'Red Sea Court',    'Jeddah', 'Al Rawdah District, Jeddah'),
    ('E', 'Gulf View',        'Dammam', 'Al Faisaliyah District, Dammam'),
    ('F', 'Pearl Gardens',    'Dammam', 'Al Shati District, Dammam')
) AS b (code, name, city, address)
JOIN cities c ON c.name_en = b.city;

-- 6 buildings x 25 units (5 floors x 5 units) = 150 units.
-- Per building index g (1..25) + building offset: g % 15 = 0 -> maintenance, g % 15 in (5, 10) -> vacant.
INSERT INTO units (building_id, code, floor, bedrooms, area_sqm, monthly_rent, status)
SELECT
    b.id,
    b.code || '-' || (f.floor * 100 + n.n),
    f.floor,
    1 + (g.g % 4),
    55 + (1 + (g.g % 4)) * 30 + (g.g % 7) * 3,
    (CASE c.name_en WHEN 'Riyadh' THEN 3500 WHEN 'Jeddah' THEN 3000 ELSE 2500 END)
        + (1 + (g.g % 4)) * 1200 + f.floor * 50,
    CASE
        WHEN (b.id * 25 + g.g) % 15 = 0 THEN 'MAINTENANCE'
        WHEN (b.id * 25 + g.g) % 15 IN (5, 10) THEN 'VACANT'
        ELSE 'OCCUPIED'
    END
FROM buildings b
JOIN cities c ON c.id = b.city_id
CROSS JOIN generate_series(1, 5) AS f (floor)
CROSS JOIN generate_series(1, 5) AS n (n)
CROSS JOIN LATERAL (SELECT (f.floor - 1) * 5 + n.n AS g) AS g;

-- One tenant per occupied unit. Name pairs are unique across the 120 tenants.
WITH first_names (idx, en, ar) AS (VALUES
    (0, 'Mohammed', 'محمد'), (1, 'Abdullah', 'عبدالله'), (2, 'Khalid', 'خالد'),
    (3, 'Fahad', 'فهد'), (4, 'Sultan', 'سلطان'), (5, 'Faisal', 'فيصل'),
    (6, 'Ahmed', 'أحمد'), (7, 'Omar', 'عمر'), (8, 'Yusuf', 'يوسف'),
    (9, 'Hassan', 'حسن'), (10, 'Ali', 'علي'), (11, 'Saad', 'سعد'),
    (12, 'Nasser', 'ناصر'), (13, 'Turki', 'تركي'), (14, 'Majed', 'ماجد'),
    (15, 'Salman', 'سلمان'), (16, 'Noura', 'نورة'), (17, 'Sara', 'سارة'),
    (18, 'Reem', 'ريم'), (19, 'Lama', 'لمى')
), last_names (idx, en, ar) AS (VALUES
    (0, 'Al-Harbi', 'الحربي'), (1, 'Al-Qahtani', 'القحطاني'), (2, 'Al-Otaibi', 'العتيبي'),
    (3, 'Al-Ghamdi', 'الغامدي'), (4, 'Al-Zahrani', 'الزهراني'), (5, 'Al-Shehri', 'الشهري'),
    (6, 'Al-Dosari', 'الدوسري'), (7, 'Al-Mutairi', 'المطيري'), (8, 'Al-Shammari', 'الشمري'),
    (9, 'Al-Anazi', 'العنزي'), (10, 'Al-Subaie', 'السبيعي'), (11, 'Al-Juhani', 'الجهني')
), occupied AS (
    SELECT u.id AS unit_id, u.monthly_rent, row_number() OVER (ORDER BY u.id) - 1 AS x
    FROM units u
    WHERE u.status = 'OCCUPIED'
)
INSERT INTO tenants (id, name_en, name_ar, phone, email, preferred_language)
SELECT
    o.x + 1,
    f.en || ' ' || l.en,
    f.ar || ' ' || l.ar,
    '+9665' || lpad((10000000 + o.x * 7919)::text, 8, '0'),
    lower(f.en) || '.' || lower(replace(l.en, '-', '')) || (o.x + 1) || '@example.test',
    CASE WHEN o.x % 3 = 0 THEN 'ar' ELSE 'en' END
FROM occupied o
JOIN first_names f ON f.idx = o.x % 20
JOIN last_names l ON l.idx = (o.x / 10) % 12;

SELECT setval('tenants_id_seq', (SELECT max(id) FROM tenants));

-- One active lease per occupied unit, started 12 months before the current month.
INSERT INTO leases (unit_id, tenant_id, start_date, end_date, monthly_rent, active)
SELECT o.unit_id, o.x + 1,
       (date_trunc('month', CURRENT_DATE) - interval '11 months')::date,
       (date_trunc('month', CURRENT_DATE) + interval '13 months' - interval '1 day')::date,
       o.monthly_rent, TRUE
FROM (
    SELECT u.id AS unit_id, u.monthly_rent, row_number() OVER (ORDER BY u.id) - 1 AS x
    FROM units u WHERE u.status = 'OCCUPIED'
) o;

-- 12 months of payments per lease.
--  * "chronic" tenants (tenant id % 11 = 7) have not paid for the last 4 months.
--  * everyone else pays within 0-9 days of the due date; some recent months are missed.
INSERT INTO payments (lease_id, period, due_date, amount, paid_amount, paid_on)
SELECT p.lease_id, p.period, p.period, p.amount,
       CASE WHEN p.paid_on IS NULL THEN 0 ELSE p.amount END,
       p.paid_on
FROM (
    SELECT
        s.lease_id,
        s.amount,
        s.period,
        CASE
            WHEN s.tenant_id % 11 = 7 AND s.m >= -3 THEN NULL
            WHEN s.m = -1 AND s.h % 13 = 0 THEN NULL
            WHEN s.m = 0 AND s.tenant_id % 4 = 0 THEN NULL
            WHEN s.period + (s.h % 10) > CURRENT_DATE THEN NULL
            ELSE s.period + (s.h % 10)
        END AS paid_on
    FROM (
        SELECT
            l.id AS lease_id,
            l.tenant_id,
            l.monthly_rent AS amount,
            m.m,
            (date_trunc('month', CURRENT_DATE) + (m.m * interval '1 month'))::date AS period,
            ((l.tenant_id * 31 + m.m * 17 + 1000) % 100)::int AS h
        FROM leases l
        CROSS JOIN generate_series(-11, 0) AS m (m)
    ) s
) p;
