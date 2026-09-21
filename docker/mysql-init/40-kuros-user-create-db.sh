#!/bin/bash
# kuros-user 独立库与授权（切片 #10 / split-05）
#
# 为什么用 .sh 而不是 .sql：GRANT 的目标账号必须跟随 .env 的 MYSQL_USER（文档明确
# 允许替换凭据），.sql 无法展开环境变量；而写死账号名在 MySQL 8 下会因 GRANT
# 不再隐式建号而报 1410，entrypoint 带 set -e，初始化直接失败 → 整栈起不来。
#
# 为什么直接用 mysql CLI 而非 entrypoint 的 docker_process_sql 函数：mysql:8.0 的
# entrypoint 对带可执行位的 .sh 走子进程执行（函数不可见），对普通 .sh 走 source；
# CLI + 已导出的环境变量在两种模式下都成立（与官方文档的 .sh 示例同款写法）。
#
# 注意：init 脚本仅对全新 volume 生效（entrypoint 只在首次初始化时执行）。
# 已有 volume 需手动执行本文件：
#   docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' \
#     < docker/mysql-init/40-kuros-user-create-db.sh
set -e

mysql --protocol=socket -uroot -hlocalhost -p"$MYSQL_ROOT_PASSWORD" <<EOF
CREATE DATABASE IF NOT EXISTS kuros_user DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON kuros_user.* TO '${MYSQL_USER:-kuros}'@'%';
FLUSH PRIVILEGES;
EOF
