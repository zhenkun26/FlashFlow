local meta = KEYS[1]
local admissions = KEYS[2]
local users = KEYS[3]
local deadlines = KEYS[4]
local version = ARGV[1]
local generation = ARGV[2]
local fence = ARGV[3]
local admissionId = ARGV[4]
local userDigest = ARGV[5]
local state = ARGV[6]
local deadline = tonumber(ARGV[7])
local consume = ARGV[8]

if redis.call('HGET', meta, 'version') ~= version then return {'VERSION_MISMATCH'} end
if redis.call('HGET', meta, 'generation') ~= generation then return {'STALE_GENERATION'} end
if redis.call('HGET', meta, 'fence') ~= fence then return {'FENCE_LOST'} end
if redis.call('HGET', meta, 'state') ~= 'INITIALIZING' then return {'NOT_INITIALIZING'} end
if redis.call('HEXISTS', admissions, admissionId) == 1 then return {'SEEDED'} end
if redis.call('HEXISTS', users, userDigest) == 1 then return {'USER_ACTIVE'} end
if consume == '1' then
  local remaining = tonumber(redis.call('HGET', meta, 'remaining') or '-1')
  if remaining <= 0 then return {'NO_CAPACITY'} end
  redis.call('HINCRBY', meta, 'remaining', -1)
end
redis.call('HSET', admissions, admissionId, state .. '|' .. userDigest .. '|' .. deadline)
redis.call('HSET', users, userDigest, admissionId)
if state == 'HELD' or state == 'QUARANTINED' then redis.call('ZADD', deadlines, deadline, admissionId) end
return {'SEEDED'}
