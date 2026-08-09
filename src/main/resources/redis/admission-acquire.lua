local current = KEYS[1]
local version = ARGV[1]
local admissionId = ARGV[2]
local userDigest = ARGV[3]
local deadline = tonumber(ARGV[4])

if redis.call('EXISTS', current) == 0 then return {'NOT_READY', '', ''} end
if redis.call('HGET', current, 'version') ~= version then return {'VERSION_MISMATCH', '', ''} end
if redis.call('HGET', current, 'state') ~= 'READY' then return {'NOT_READY', '', ''} end
local generation = redis.call('HGET', current, 'generation')
if not generation then return {'NOT_READY', '', ''} end
local expectedMeta = KEYS[2] .. generation .. ':meta'
local expectedAdmissions = KEYS[2] .. generation .. ':admissions'
local expectedUsers = KEYS[2] .. generation .. ':users'
local expectedDeadlines = KEYS[2] .. generation .. ':deadlines'
if redis.call('HGET', expectedMeta, 'state') ~= 'READY' then return {'NOT_READY', '', generation} end
if redis.call('HGET', expectedMeta, 'version') ~= version then return {'VERSION_MISMATCH', '', generation} end

local existing = redis.call('HGET', expectedAdmissions, admissionId)
if existing then return {'REPLAY', admissionId, generation} end
local owner = redis.call('HGET', expectedUsers, userDigest)
if owner then return {'USER_ACTIVE', owner, generation} end
local remaining = tonumber(redis.call('HGET', expectedMeta, 'remaining') or '-1')
if remaining <= 0 then return {'NO_TOKEN', '', generation} end

redis.call('HINCRBY', expectedMeta, 'remaining', -1)
redis.call('HSET', expectedAdmissions, admissionId, 'HELD|' .. userDigest .. '|' .. deadline)
redis.call('HSET', expectedUsers, userDigest, admissionId)
redis.call('ZADD', expectedDeadlines, deadline, admissionId)
return {'ADMITTED', admissionId, generation}
