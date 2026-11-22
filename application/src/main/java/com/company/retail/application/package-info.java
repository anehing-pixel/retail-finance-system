/**
 * 应用层：用例编排层，事务边界只在这里（{@code @Transactional}）。
 *
 * <p><b>本模块不依赖 common</b>（防 Maven 循环，对齐 floorplan 基线）：
 * 跨切面机制（鉴权/响应/异常/outbox 端口）由各服务装配 common 提供，
 * 应用服务只依赖 domain 与本层 port。</p>
 *
 * <p>W2 为工程地基轮：业务用例自 S2 起按 R05 流程逐个落入本层；
 * 本轮不预置任何「空的用例类」（不把占位说成已实现，P-11）。</p>
 */
package com.company.retail.application;
