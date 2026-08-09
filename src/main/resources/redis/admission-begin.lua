local current = KEYS[1]
local meta = KEYS[2]
local admissions = KEYS[3]
local users = KEYS[4]
local deadlines = KEYS[5]
local version = ARGV[1]
local generation = ARGV[2]
local capacity = tonumber(ARGV[3])
local fence = ARGV[4]
local leaseMillis = tonumber(ARGV[5])

if not capacity or capacity < 0 or not leaseMillis or leaseMillis <= 0 then return {'INVALID_ARGUMENT'} end
local serverTime = redis.call('TIME')
local nowMillis = (tonumber(serverTime[1]) * 1000) + math.floor(tonumber(serverTime[2]) / 1000)
local existingFence = redis.call('HGET', current, 'fence')
local existingDeadline = tonumber(redis.call('HGET', current, 'fence_deadline') or '0')
if existingFence and existingFence ~= fence and existingDeadline > nowMillis then return {'FENCE_BUSY'} end
local fenceDeadline = nowMillis + leaseMillis

redis.call('HSET', current, 'state', 'INITIALIZING', 'fence', fence,
  'fence_deadline', fenceDeadline, 'version', version)
redis.call('DEL', meta, admissions, users, deadlines)
redis.call('HSET', meta, 'version', version, 'generation', generation, 'state', 'INITIALIZING',
  'initial', capacity, 'remaining', capacity, 'fence', fence, 'fence_deadline', fenceDeadline)
return {'INITIALIZING', generation}
