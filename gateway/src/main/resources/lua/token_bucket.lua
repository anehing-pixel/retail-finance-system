-- 网关令牌桶（原子扣减）
-- KEYS[1] = 桶键
-- ARGV[1] = 桶容量（burst capacity）
-- ARGV[2] = 补充速率（令牌 / 秒）
-- ARGV[3] = 当前时间（毫秒）
-- ARGV[4] = 本次消耗（默认 1）
-- 返回 1 = 放行，0 = 限流
--
-- 为什么用 Lua 而不是「HGET → 计算 → HSET」：
-- 三步不是原子的，两个并发请求会读到同一个令牌数并都放行，限流形同虚设。
-- Redis 单线程执行脚本，脚本内天然互斥。
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local cost = tonumber(ARGV[4])

local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts = tonumber(data[2])
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end

-- 按经过时间补充令牌（令牌可以是分数，Lua 数值天然支持）
local elapsed = math.max(0, now - ts)
tokens = math.min(capacity, tokens + (elapsed / 1000.0) * rate)

local allowed = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
-- 空闲过期：按「装满所需时间」再放宽 1s，避免长期空桶占用内存
local ttl = math.ceil((capacity / math.max(rate, 0.001)) * 1000) + 1000
redis.call('PEXPIRE', KEYS[1], ttl)
return allowed
