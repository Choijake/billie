-- KEYS[1]: STATE_KEY
-- ARGV[1]: newIndex, ARGV[2]: now(ms), ARGV[3]: ttlSec
local status = redis.call('HGET', KEYS[1], 'status')
if status ~= 'RUNNING' then return 0 end
local cur = redis.call('HGET', KEYS[1], 'newIndex')
if not cur or cur == '' then
  redis.call('HSET', KEYS[1], 'newIndex', ARGV[1], 'updatedAt', ARGV[2])
  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
  return 1
else
  return 0
end
