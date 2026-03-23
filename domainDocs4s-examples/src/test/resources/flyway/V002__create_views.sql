CREATE VIEW user_transaction_summary AS
    SELECT u.id, u.name, COUNT(t.id) as tx_count, SUM(t.amount) as total_amount
    FROM users u
    JOIN transactions t ON t.user_id = u.id
    GROUP BY u.id, u.name;
