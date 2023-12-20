# API 接口开放平台

## 项目介绍

基于 Spring Boot + Dubbo+Gateway 的 API 接口开放调用平台。管理员可以接入并发布接口，可视化各接口调用情况；用户可以开通接口调用权限、浏览接口及在线调试，并通过客户端 SDK 轻松调用接口。

## 业务流程

![微信截图_20231201100514](https://663450fd.telegraph-image-ban.pages.dev/file/3c495d6bd112b337881ba.jpg)

## 快速启动

### 前端

环境要求：Node.js >= 16

安装依赖：

```shell
yarn or npm install
```

启动：

```shell
yarn run dev or npm run start:dev
```

部署：

```shell
yarn build or npm run build
```

### 后端

执行 sql 目录下的 create_table.sql

## 技术选型

#### 前端

Ant Design Pro

React

Ant Design Procomponents

Umi

Umi Request

#### 后端

Java Spring Boot

Spring Boot Starter（SDK开发）

Dubbo（RPC）

Nacos

Spring Cloud Gateway（网关、限流、日志实现）

## 功能展示

### 接口首页

![API开放平台首页](https://663450fd.telegraph-image-ban.pages.dev/file/0364237c2c35a9036fb89.jpg)

### 接口管理

![API开放平台接口管理页面](https://663450fd.telegraph-image-ban.pages.dev/file/10188657cec5ef48713af.jpg)

### 接口详情

![API开放平台接口详情页面](https://663450fd.telegraph-image-ban.pages.dev/file/c4e2fe27bf24fb5637399.jpg)

### 接口分析

![API开放平台接口分析页面](https://663450fd.telegraph-image-ban.pages.dev/file/3b94d26b18e8ca4e5f296.jpg)