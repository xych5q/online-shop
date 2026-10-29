package com.group1.onlineshop.controller;

import com.group1.onlineshop.service.BuyerService;
import com.group1.onlineshop.service.IntentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 买家端控制器（挑战需求 A · 买家账号域，任务 D-1 / D-2 / D-4）。
 *
 * <p>本文件由董嘉润独占（见 .github/CODEOWNERS），只放买家账号域的 5 个接口：
 * <pre>
 * POST /api/buyers/register   注册（无鉴权）
 * POST /api/buyers/login      登录（无鉴权）
 * POST /api/buyers/logout     退出登录（买家登录态）
 * GET  /api/buyers/me         当前登录买家（买家登录态）
 * GET  /api/buyers/history    我的历史（买家登录态，10 条/页）
 * </pre>
 *
 * <p><b>路径口径</b>：按 W-2（接口设计 v2 §0.1）统稿结论，买家端路径<b>唯一</b>为 {@code /api/buyers/*}。
 * 早期为兼容 Wiki 旧稿而同时挂载的 {@code /api/buyer/*} 单数字别名已于 2026-10-29 删除
 * （评审 PR #5 时清理，见接口设计 v2 §0.1）；调用方请统一改用复数字形。
 *
 * <p><b>本 PR 为纯增量</b>：迭代 1 的 4 个买家端点（{@code POST /api/intents} 与三个凭口令码接口）
 * 原样保留未动——按 Wiki《接口设计》v2 §0.1 / §0.2，它们的删除与迁移属<b>队列与交易域（王振涛 W-3）</b>：
 * 口令码机制整体废弃（C6 / A.2.2-9 / A.2.4-2），{@code POST /api/intents} 迁入 {@code IntentController}。
 * 涉及本文件时请 @jrdong666 审核（CODEOWNERS）。保留期间不影响本域 5 个新接口。
 *
 * <p>鉴权：买家 token 从请求头 {@code X-Token} 取（兼容 {@code Authorization: Bearer}），
 * 失效统一 401「未登录或登录已失效」；与卖家 token 分表校验，不混用。
 */
@RestController
@RequiredArgsConstructor
public class BuyerController {

    private final BuyerService buyerService;
    private final IntentService intentService;

    /**
     * 获取当前在售 / 交易中商品（基线遗留）。
     *
     * <p>迭代 2 已判定<b>废弃</b>：多商品场景下由 {@code GET /api/products} 与
     * {@code GET /api/products/{id}} 承接（嵇宇锋 J-1，见接口设计 v2 §0.2）。
     * 保留仅作过渡——新前端页面切换完成后可整体删除本方法。
     */
    @GetMapping("/api/product")
    public Map<String, Object> product() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("product", intentService.activeProduct());
        return result;
    }

    // W-3（王振涛）：以下 4 个基线遗留端点已删除——
    //   POST /api/intents                     → 迁入 IntentController（登录态 + {productId}）
    //   GET  /api/intents/lookup/{code}       → 口令码机制废弃（C6 / A.2.2-6）
    //   PUT  /api/intents/lookup/{code}       → 同上（A.2.4-2）
    //   POST /api/intents/lookup/{code}/cancel→ 买家无取消入口（A.2.2-9）
    // 删除依据见接口设计 v2 §0.1，端点迁移后 IntentService 的对应方法同步移除。

    /**
     * 买家注册（FR-D1）。
     * 入参 {username,password,phone,phoneConfirm,defaultLocation?}：
     * 用户名 3–20 位字母数字下划线、忽略大小写唯一；密码 ≥8 且含字母与数字；电话必填并二次确认；
     * 默认交易地点选填。成功返回 {ok:true,buyer:{id,username,phone,defaultLocation,createdAt}}。
     */
    @PostMapping("/api/buyers/register")
    public Map<String, Object> register(@RequestBody Map<String, Object> body) {
        return buyerService.register(str(body.get("username")), str(body.get("password")),
                str(body.get("phone")), str(body.get("phoneConfirm")), str(body.get("defaultLocation")));
    }

    /**
     * 买家登录（FR-D2）。
     * 入参 {username,password}；成功返回 {token,username}，并刷新 token（旧 token 立即失效）；
     * 失败抛 401「用户名或密码错误」。
     */
    @PostMapping("/api/buyers/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        return buyerService.login(str(body.get("username")), str(body.get("password")));
    }

    /** 退出登录（FR-D2）：清空 token，成功返回 {ok:true}。未登录抛 401「未登录或登录已失效」。 */
    @PostMapping("/api/buyers/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        buyerService.logout(token(request));
        return ok();
    }

    /**
     * 当前登录买家（FR-D2）。
     * 返回 {buyer:{id,username,phone,defaultLocation,createdAt}}；供下单页回显联系电话（附三缓解措施）。
     */
    @GetMapping("/api/buyers/me")
    public Map<String, Object> me(HttpServletRequest request) {
        return buyerService.me(token(request));
    }

    /**
     * 我的历史（FR-D4）。
     * 返回 {page,totalPages,total,items[]}；每条含商品名称、下单时间、交易结果（待交易/成功/失败）、交易时间；
     * 按提交时间倒序、10 条/页，不含价格与图片。{@code ?page=n} 从 1 起。
     */
    @GetMapping("/api/buyers/history")
    public Map<String, Object> history(HttpServletRequest request,
                                       @RequestParam(required = false) Integer page) {
        return buyerService.history(token(request), page);
    }

    /** 从 X-Token 或 Authorization: Bearer 头取 token（与 SellerController 保持一致） */
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

    /** 统一成功返回 {ok:true} */
    private Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }
}
