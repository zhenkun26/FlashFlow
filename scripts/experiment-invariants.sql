SELECT
  COALESCE(SUM(initial_stock), 0) AS initial_stock,
  COALESCE(SUM(available_stock), 0) AS available_stock,
  COALESCE(SUM(reserved_stock), 0) AS reserved_stock,
  COALESCE(SUM(sold_stock), 0) AS sold_stock,
  (SELECT COUNT(*) FROM orders WHERE status IN ('PENDING_PAYMENT', 'PAID')) AS effective_orders,
  (SELECT COUNT(*) FROM purchase_claim) AS effective_claims,
  (SELECT COUNT(*) FROM inventory_reservation WHERE status = 'RESERVED') AS reserved_reservations,
  (SELECT COUNT(*) FROM inventory_movement) AS movements,
  (SELECT COUNT(*) FROM activity_sku_stock
   WHERE available_stock < 0 OR reserved_stock < 0 OR sold_stock < 0
      OR initial_stock <> available_stock + reserved_stock + sold_stock) AS negative_or_unbalanced_stocks,
  (SELECT COUNT(*) FROM orders o LEFT JOIN purchase_claim c ON c.order_id = o.id
   WHERE o.status IN ('PENDING_PAYMENT', 'PAID') AND c.order_id IS NULL) AS effective_orders_without_claims,
  (SELECT COUNT(*) FROM purchase_claim c JOIN orders o ON o.id = c.order_id
   WHERE o.status NOT IN ('PENDING_PAYMENT', 'PAID')) AS claims_without_effective_orders,
  (SELECT COUNT(*) FROM orders o JOIN inventory_reservation r ON r.order_id = o.id
   WHERE (o.status = 'PENDING_PAYMENT' AND r.status <> 'RESERVED')
      OR (o.status = 'PAID' AND r.status <> 'CONFIRMED')
      OR (o.status = 'CLOSED_UNPAID' AND r.status <> 'RELEASED')) AS order_reservation_mismatches,
  (SELECT COUNT(*) FROM (
       SELECT operation_id FROM inventory_movement GROUP BY operation_id HAVING COUNT(*) > 1
   ) duplicate_operations) AS duplicate_movement_operations,
  (SELECT COUNT(*) FROM (
       SELECT s.id
       FROM activity_sku_stock s
       LEFT JOIN orders o ON o.activity_sku_id = s.id AND o.status IN ('PENDING_PAYMENT', 'PAID')
       GROUP BY s.id, s.initial_stock
       HAVING COUNT(o.id) > s.initial_stock
   ) excessive_orders) AS effective_orders_over_initial_stock
FROM activity_sku_stock;
