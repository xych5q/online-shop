package com.group1.onlineshop.controller;

import com.group1.onlineshop.service.ProductService;
import com.group1.onlineshop.service.SellerService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 卖家端控制器：12 个 /api/seller/ 接口。
 * 业务规则与入参出参以 docs/design/接口设计.md 为准，规则逐条翻译自可运行原型，未做改动。
 * 鉴权：除 login 外，均从请求头 X-Token（或 Authorization: Bearer）取 token，校验失败抛 401「未登录或登录已失效」。
 */
@RestController
@RequiredArgsConstructor
public class SellerController {

    private final SellerService sellerService;
    private final ProductService productService;

    /**
     * 卖家登录：入参 {username,password}。
     * 成功返回 {token,username} 并刷新 token（旧 token 立即失效）；失败抛 401「用户名或密码错误」。无鉴权。
     */
    @PostMapping("/api/seller/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        return sellerService.login(str(body.get("username")), str(body.get("password")));
    }

    /**
     * 修改密码：入参 {oldPassword,newPassword}。
     * 规则：验原密码（错误 400「原密码错误」）；新密码 <8 位 400「新密码长度不能少于 8 位」；成功后 BCrypt 重新编码。
     */
    @PostMapping("/api/seller/password")
    public Map<String, Object> changePassword(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        return sellerService.changePassword(token(request),
                str(body.get("oldPassword")), str(body.get("newPassword")));
    }

    /**
     * 工作台总览。
     * 返回 {detail:{product,queue[],currentDeal,pendingFailed[]},canPublish}；queue 带 position；
     * currentDeal 与 pendingFailed 绝不包含口令码；canPublish=true 表示当前无在售/交易中商品。
     */
    @GetMapping("/api/seller/overview")
    public Map<String, Object> overview(HttpServletRequest request) {
        return sellerService.overview(token(request));
    }

    /**
     * 发布商品：入参 {name,description,price,image?}，image 为 data:image/(jpeg|png);base64,... 字符串。
     * 规则：已有在售或交易中商品 400「已有商品在售或交易中，需先下架」；name 必填且 ≤100 字；description ≤2000 字；
     * price >0 且保留两位小数；图片解码存 app.upload.dir，只把文件名存进 Product.image；发布后状态直接为在售。
     */
    @PostMapping("/api/seller/products")
    public Map<String, Object> publish(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        requireAuth(request);
        return productService.publish(body);
    }

    /**
     * 手动冻结：仅 ON_SALE 可冻结，置 status=FROZEN、frozenBy='manual'、frozenAt=now。
     */
    @PostMapping("/api/seller/products/{id}/freeze")
    public Map<String, Object> freeze(HttpServletRequest request, @PathVariable long id) {
        requireAuth(request);
        return productService.freeze(id);
    }

    /**
     * 手动解冻：仅 frozenBy='manual' 可解冻，否则 400「交易中的商品不能手动解冻」（交易冻结必须先标记交易结果）。
     */
    @PostMapping("/api/seller/products/{id}/unfreeze")
    public Map<String, Object> unfreeze(HttpServletRequest request, @PathVariable long id) {
        requireAuth(request);
        return productService.unfreeze(id);
    }

    /**
     * 与队首进入交易：取队列首位置 IN_TRANSACTION，商品自动冻结（frozenBy='auto'）；队列为空 400「当前没有排队的买家」。
     */
    @PostMapping("/api/seller/products/{id}/deal")
    public Map<String, Object> deal(HttpServletRequest request, @PathVariable long id) {
        requireAuth(request);
        return productService.deal(id);
    }

    /**
     * 标记交易结果：入参 {result:"success"|"fail"}。
     * success → 胜出意向成功、队列剩余转失败、商品下架进历史；fail → 当前意向转失败（待处置），
     * 队列有人自动递补、队列无人商品恢复在售。
     */
    @PostMapping("/api/seller/products/{id}/deal/result")
    public Map<String, Object> dealResult(HttpServletRequest request, @PathVariable long id,
                                         @RequestBody Map<String, Object> body) {
        requireAuth(request);
        return productService.dealResult(id, str(body.get("result")));
    }

    /**
     * 手动下架：该商品队列里所有 QUEUED 意向置 FAILED 且 disposed=true，商品置 OFF_SHELF、closedAt=now；
     * 交易冻结中的商品须先标记交易结果。
     */
    @PostMapping("/api/seller/products/{id}/offshelf")
    public Map<String, Object> offshelf(HttpServletRequest request, @PathVariable long id) {
        requireAuth(request);
        return productService.offshelf(id);
    }

    /**
     * 处置失败意向：入参 {action:"void"|"requeue"}。
     * void=作废；requeue=沿用原口令码重新排队（submittedAt 刷新排到队尾）。
     */
    @PostMapping("/api/seller/intents/{id}/dispose")
    public Map<String, Object> dispose(HttpServletRequest request, @PathVariable long id,
                                       @RequestBody Map<String, Object> body) {
        requireAuth(request);
        return productService.dispose(id, str(body.get("action")));
    }

    /**
     * 历史商品列表（分页）：只查已下架商品，按 closedAt 倒序，每页 10 条，
     * 返回 {page,totalPages,total,items[]}；page 从 1 开始（?page=n）。
     */
    @GetMapping("/api/seller/history")
    public Map<String, Object> history(HttpServletRequest request,
                                       @RequestParam(required = false) Integer page) {
        return sellerService.history(token(request), page);
    }

    /**
     * 历史商品详情与意向流水：意向按 submittedAt 正序，流水里绝不含口令码 code；只读无修改接口。
     */
    @GetMapping("/api/seller/history/{id}")
    public Map<String, Object> historyDetail(HttpServletRequest request, @PathVariable long id) {
        return sellerService.historyDetail(token(request), id);
    }

    /**
     * 买家侧：获取当前在售/交易中商品（无鉴权），无商品时 product 为 null。
     * TODO: 买家端控制器就绪后由其接管本接口，当前仅为联调与验收提供。
     */
    @GetMapping("/api/product")
    public Map<String, Object> product() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("product", productService.activeProduct());
        return result;
    }

    /** 鉴权：token 无效时由 SellerService 抛 401「未登录或登录已失效」 */
    private void requireAuth(HttpServletRequest request) {
        sellerService.requireAuth(token(request));
    }

    /** 从 X-Token 或 Authorization: Bearer 头取 token（与原型一致，两者取其一） */
    private String token(HttpServletRequest request) {
        String t = request.getHeader("X-Token");
        if (t == null || t.isBlank()) {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.length() > 7 && auth.substring(0, 7).equalsIgnoreCase("Bearer ")) {
                t = auth.substring(7);
            }
        }
        return t == null ? "" : t.trim();
    }

    /** null 安全的字符串转换 */
    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
