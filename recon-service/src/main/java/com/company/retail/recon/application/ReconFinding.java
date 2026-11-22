package com.company.retail.recon.application;

import java.util.Map;

/**
 * 对拍发现项（一轮对拍中检出的单条不一致；application 层落 recon_diffs）。
 */
public record ReconFinding(String invCode, String entityType, String entityId,
                           Map<String, Object> detail) {
}
