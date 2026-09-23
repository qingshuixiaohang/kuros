-- Canal 复制账号（切片 #14 se-02）：canal-server 伪装成 MySQL slave 订阅 binlog 所需。
--
-- 为什么单独建号而不用 MYSQL_USER：canal 需要 REPLICATION SLAVE/CLIENT 权限（拉 binlog dump），
-- 业务账号 kuros 只有库级 DML 权限，授予复制权限会放大业务账号的爆炸半径，故隔离专用账号。
-- 权限最小集：SELECT（回查表结构/初始 position）+ REPLICATION SLAVE（订阅 binlog）+ REPLICATION CLIENT（SHOW MASTER STATUS）。
-- 复制账号必须 ON *.*（binlog 是实例级的，不限单库）。
--
-- 为什么显式 mysql_native_password：mysql:8.0 默认 caching_sha2_password，非 SSL 首连要取服务器公钥，
-- 而 canal 的 MySQL 驱动不传 allowPublicKeyRetrieval=true → caching_sha2 握手直接失败（canal+MySQL8 经典坑）。
-- mysql_native_password 在 8.0 仍可用，免公钥往返，canal 稳妥握手。（若未来升 MySQL 8.4，该插件默认禁用，需另配。）
CREATE USER IF NOT EXISTS 'canal'@'%' IDENTIFIED WITH mysql_native_password BY 'canal_dev_password';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';
FLUSH PRIVILEGES;
