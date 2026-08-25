-- ZipRun Sprint 1 seed — idempotent for test context reloads
DELETE FROM reassignment_suggestions;
DELETE FROM orders;
DELETE FROM agents;

INSERT INTO agents (id, name, active_order_count, status, current_zone, max_capacity, manual_override) VALUES
  ('AGT-001', 'Priya Sharma',  3, 'BUSY',      NULL, 3, FALSE),
  ('AGT-002', 'Rahul Verma',   0, 'AVAILABLE', NULL, 3, FALSE),
  ('AGT-003', 'Ananya Iyer',   2, 'BUSY',      NULL, 3, FALSE),
  ('AGT-004', 'Kiran Nair',    0, 'AVAILABLE', NULL, 3, FALSE),
  ('AGT-005', 'Deepak Mehta',  3, 'BUSY',      NULL, 3, FALSE);

INSERT INTO orders (id, description, assigned_agent_id, status, created_at, weight_class, pickup_zone, dropoff_zone) VALUES
  ('ORD-001', 'Electronics — Koramangala to Indiranagar', 'AGT-001', 'ASSIGNED', CURRENT_TIMESTAMP, NULL, NULL, NULL),
  ('ORD-002', 'Groceries — HSR Layout to BTM',          'AGT-001', 'ASSIGNED', CURRENT_TIMESTAMP, NULL, NULL, NULL),
  ('ORD-003', 'Pharma — Whitefield to Marathahalli',      'AGT-003', 'ASSIGNED', CURRENT_TIMESTAMP, NULL, NULL, NULL),
  ('ORD-004', 'Documents — MG Road to Jayanagar',        'AGT-005', 'ASSIGNED', CURRENT_TIMESTAMP, NULL, NULL, NULL),
  ('ORD-005', 'Food — Bellandur to Electronic City',      'AGT-005', 'ASSIGNED', CURRENT_TIMESTAMP, NULL, NULL, NULL),
  ('ORD-006', 'Apparel — Malleshwaram to Rajajinagar',    'AGT-005', 'ASSIGNED', CURRENT_TIMESTAMP, NULL, NULL, NULL),
  ('ORD-007', 'Books — Banashankari to JP Nagar',         'AGT-003', 'ASSIGNED', CURRENT_TIMESTAMP, NULL, NULL, NULL),
  ('ORD-008', 'Hardware — Peenya to Yeshwanthpur',        'AGT-001', 'ASSIGNED', CURRENT_TIMESTAMP, NULL, NULL, NULL);
