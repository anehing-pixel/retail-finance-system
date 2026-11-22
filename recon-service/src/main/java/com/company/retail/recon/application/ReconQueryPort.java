package com.company.retail.recon.application;

import java.util.List;

/**
 * 对拍查询端口（只读跨真相源；SQL 侧用 V14 对拍视图 core.v_recon_inv1/2/3/5，
 * INV-4 在实现内扫 core.outbox_events payload 键白名单）。
 */
public interface ReconQueryPort {

    /** 逐视点收集不一致发现项（无差异返回空表）。 */
    List<ReconFinding> collectFindings();
}
