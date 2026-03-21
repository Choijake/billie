-- KEYS[1]: STATE_KEY, KEYS[2]: FENCING_SEQ_KEY
-- ARGV[1]: runId, ARGV[2]: cutoff(ms), ARGV[3]: now(ms), ARGV[4]: ttlSec
local status = redis.call('HGET', KEYS[1], 'status')
if status == 'RUNNING' then
  return 0
else
  local tok = redis.call('INCR', KEYS[2])
  redis.call('HSET', KEYS[1],
    'runId', ARGV[1],
    'cutoff', ARGV[2],
    'newIndex', '',
    'lastId', '0',
    'processed', '0',
    'fencingToken', tostring(tok),
    'status', 'RUNNING',
    'startedAt', ARGV[3],
    'updatedAt', ARGV[3]
  )
  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
  return tok
end
