local current = KEYS[1]
local base = KEYS[2]
local version = ARGV[1]
local generation = ARGV[2]
local admissionId = ARGV[3]
if redis.call('HGET', current, 'version') ~= version then return {'VERSION_MISMATCH', generation} end
if redis.call('HGET', current, 'generation') ~= generation then return {'STALE_GENERATION', generation} end
local admissions = base .. generation .. ':admissions'
local value = redis.call('HGET', admissions, admissionId)
if not value then return {'NOT_FOUND', generation} end
local state = string.match(value, '^([^|]+)')
if state == 'RELEASED' then return {'ALREADY_RELEASED', generation} end
if state ~= 'QUARANTINED' then
  local updated = string.gsub(value, '^' .. state, 'QUARANTINED')
  redis.call('HSET', admissions, admissionId, updated)
end
return {'QUARANTINED', generation}
