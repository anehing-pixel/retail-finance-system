package com.company.retail.titlerelease.domain;

/**
 * 证据链八条闭合校验（R05 §6；八条全满足才可进第 7 步改写保留状态）。
 * 纯领域函数：输入证据快照，输出逐条判定。零 Spring、可纯单测。
 */
public final class EvidenceChainPolicy {

    private EvidenceChainPolicy() {
    }

    /** 证据快照（infrastructure/application 从表态组装；领域层不感知 DB）。 */
    public record EvidenceSnapshot(boolean loanTerminal, boolean balanceZero, boolean noPendingDisputes,
                                   int documentCount, boolean allDocumentsDelivered,
                                   boolean externalSubmitted, boolean externalReceiptExists,
                                   boolean receiptAuthorityExternal) {
    }

    /** 单条证据判定。 */
    public record EvidenceItem(int no, String name, boolean satisfied, String evidenceHint) {
    }

    /** 八条闭合判定（顺序与 R05 §6 一致）。 */
    public static java.util.List<EvidenceItem> evaluate(EvidenceSnapshot s) {
        java.util.List<EvidenceItem> items = new java.util.ArrayList<>();
        items.add(new EvidenceItem(1, "债权终态", s.loanTerminal(),
                "LOAN_SETTLED 事件（SETTLED / EARLY_SETTLED）"));
        items.add(new EvidenceItem(2, "余额为 0", s.balanceZero(), "结清对拍（INV-2）"));
        items.add(new EvidenceItem(3, "无未决异议", s.noPendingDisputes(), "bureau_disputes 无 OPEN 行"));
        items.add(new EvidenceItem(4, "解除文件齐", s.documentCount() == 4,
                "解除同意书/转让证明书/授权委托书/印鉴证明书"));
        items.add(new EvidenceItem(5, "交付留痕", s.allDocumentsDelivered(), "四件套 delivered_to 全非空"));
        items.add(new EvidenceItem(6, "外部申请受理", s.externalSubmitted(),
                "external_submission_ref（申请受理编号）"));
        items.add(new EvidenceItem(7, "外部登记回执", s.externalReceiptExists(),
                "registration_receipts.authority = EXTERNAL_REGISTRY"));
        items.add(new EvidenceItem(8, "回执验真/权威性", s.receiptAuthorityExternal(),
                "非 PARTNER_SELF_REPORT（C-11 唯一权威）"));
        return java.util.List.copyOf(items);
    }

    /** 八条是否全部闭合（第 7 步的前置）。 */
    public static boolean allClosed(java.util.List<EvidenceItem> items) {
        return items.stream().allMatch(EvidenceItem::satisfied);
    }
}
