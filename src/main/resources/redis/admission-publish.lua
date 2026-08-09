local current = KEYS[1]
local meta = KEYS[2]
local version = ARGV[1]
local generation = ARGV[2]
local fence = ARGV[3]

if redis.call('HGET', current, 'fence') ~= fence then return {'FENCE_LOST'} end
if redis.call('HGET', meta, 'fence') ~= fence then return {'FENCE_LOST'} end
if redis.call('HGET', meta, 'version') ~= version then return {'VERSION_MISMATCH'} end
local serverTime = redis.call('TIME')
local nowMillis = (tonumber(serverTime[1]) * 1000) + math.floor(tonumber(serverTime[2]) / 1000)
if tonumber(redis.call('HGET', current, 'fence_deadline') or '0') <= nowMillis then
  return {'FENCE_EXPIRED'}
end
redis.call('HSET', meta, 'state', 'READY')
redis.call('HSET', current, 'generation', generation, 'state', 'READY', 'version', version)
redis.call('HDEL', current, 'fence', 'fence_deadline')
return {'READY', generation}
