-- KEYS[1]: STATE_KEY
-- ARGV[1]: expectedToken, ARGV[2]: now(ms)
local tok = tonumber(redis.call('HGET', KEYS[1], 'fencingToken') or '-1')
if tok == tonumber(ARGV[1]) then
  redis.call('HSET', KEYS[1], 'status', 'SUCCEEDED', 'updatedAt', ARGV[2])
  return 1
else
  return 0
end
