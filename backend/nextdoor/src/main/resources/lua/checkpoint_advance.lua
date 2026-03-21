-- KEYS[1]: STATE_KEY
-- ARGV[1]: lastId, ARGV[2]: processed, ARGV[3]: now(ms), ARGV[4]: ttlSec
local status = redis.call('HGET', KEYS[1], 'status')
if status ~= 'RUNNING' then return 0 end
local cur = tonumber(redis.call('HGET', KEYS[1], 'lastId') or '0')
local nxt = tonumber(ARGV[1])
if nxt > cur then
  redis.call('HSET', KEYS[1],
    'lastId', ARGV[1],
    'processed', ARGV[2],
    'updatedAt', ARGV[3]
  )
  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
  return 1
else
  return 0
end
