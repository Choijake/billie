-- KEYS[1]: STATE_KEY
-- ARGV[1]: now(ms)
local status = redis.call('HGET', KEYS[1], 'status')
if status == 'RUNNING' then
  redis.call('HSET', KEYS[1], 'status', 'INDEXED', 'updatedAt', ARGV[1])
  return 1
else
  return 0
end
