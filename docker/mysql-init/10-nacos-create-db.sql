-- Nacos 配置中心专用库与账号（切片 #8）
-- 说明：mysql 官方镜像的 entrypoint 只对 MYSQL_DATABASE 授权 MYSQL_USER，
-- Nacos 需要独立的库与账号，因此在这里显式创建。
CREATE DATABASE IF NOT EXISTS nacos_config DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER IF NOT EXISTS 'nacos'@'%' IDENTIFIED BY 'nacos_dev_password';
GRANT ALL PRIVILEGES ON nacos_config.* TO 'nacos'@'%';
FLUSH PRIVILEGES;
USE nacos_config;
