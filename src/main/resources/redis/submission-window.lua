-- Window starts with the first admitted request; rejected requests do not extend or increment it.
local count = redis.call('GET', KEYS[1])
if not count then
    redis.call('SET', KEYS[1], '1', 'PX', ARGV[2])
    return {1, tonumber(ARGV[2])}
end
local ttl = redis.call('PTTL', KEYS[1])
if ttl < 0 or not tonumber(count) then
    return redis.error_reply('Invalid rate limit counter or expiry')
end
if tonumber(count) >= tonumber(ARGV[1]) then
    return {0, ttl}
end
redis.call('INCR', KEYS[1])
return {1, ttl}
