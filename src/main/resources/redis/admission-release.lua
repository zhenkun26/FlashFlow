local current = KEYS[1]
local base = KEYS[2]
local version = ARGV[1]
local generation = ARGV[2]
local admissionId = ARGV[3]
local closure = ARGV[4]
if redis.call('HGET', current, 'version') ~= version then return {'VERSION_MISMATCH', generation} end
if redis.call('HGET', current, 'generation') ~= generation then return {'STALE_GENERATION', generation} end
local meta = base .. generation .. ':meta'
local admissions = base .. generation .. ':admissions'
local users = base .. generation .. ':users'
local deadlines = base .. generation .. ':deadlines'
local value = redis.call('HGET', admissions, admissionId)
if not value then return {'NOT_FOUND', generation} end
local state, user = string.match(value, '^([^|]+)|([^|]+)')
if state == 'RELEASED' then return {'ALREADY_RELEASED', generation} end
if state == 'QUARANTINED' then return {'QUARANTINED', generation} end
if (closure == '1' and state ~= 'CONFIRMED') or (closure ~= '1' and state ~= 'HELD') then
  return {state == 'CONFIRMED' and 'ALREADY_CONFIRMED' or 'AMBIGUOUS', generation}
end
local remaining = tonumber(redis.call('HGET', meta, 'remaining') or '-1')
local initial = tonumber(redis.call('HGET', meta, 'initial') or '-1')
if remaining < 0 or initial < 0 or remaining >= initial then return {'AMBIGUOUS', generation} end
local updated = string.gsub(value, '^' .. state, 'RELEASED')
redis.call('HSET', admissions, admissionId, updated)
if redis.call('HGET', users, user) == admissionId then redis.call('HDEL', users, user) end
redis.call('ZREM', deadlines, admissionId)
redis.call('HINCRBY', meta, 'remaining', 1)
return {'RELEASED', generation}
