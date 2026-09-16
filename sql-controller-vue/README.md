# 数据库 SQL 操作台前端

Vue 3 + Vite 前端，调用 `sqlController` 后端的 `/api` 接口。

## 运行

```bash
npm install
npm run dev
```

开发服务器会将 `/api` 代理到 `http://localhost:8080`。先启动 Spring Boot 后端，再访问 Vite 输出的本地地址。

## 构建

```bash
npm run build
```

生产环境中请把构建产物部署到与后端相同的域名下，或由网关将 `/api` 转发到后端。
