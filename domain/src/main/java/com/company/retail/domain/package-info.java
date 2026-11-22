/**
 * 领域层（零 Spring）：聚合模型、领域服务、错误码与权限点语义。
 *
 * <p>依赖方向：domain ← application ← infrastructure / common ← 各服务。
 * 本模块不得 import 任何 Spring / Spring Boot 类（对齐 floorplan 基线，W2 起由编译依赖保证——
 * 本模块唯一运行依赖是中台 contracts jar，其业务相关类均为纯 Java）。</p>
 */
package com.company.retail.domain;
