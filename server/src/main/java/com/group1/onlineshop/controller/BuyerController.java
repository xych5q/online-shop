package com.group1.onlineshop.controller;

import com.group1.onlineshop.service.IntentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 买家端控制器：5 个买家接口（无账号体系，买家以「持有口令码」作为唯一凭证）。
 * 业务规则与入参出参以 docs/design/接口设计.md 为准，规则逐条翻译自可运行原型，未做改动。
 */
@RestController
@RequiredArgsConstructor
public class BuyerController {

    private final IntentService intentService;

    /**
     * 获取当前在售 / 交易中商品。
     * 返回 {product:{id,name,description,price,imageUrl,status,publishedAt}|null}；
     * 只返回在售或已冻结的商品，无商品时 product 为 null；绝不返回意向或口令码信息。
     * 原 SellerController 中的临时实现已迁入本方法（docs/design/Java实现进度.md TD-3）。
     */
    @GetMapping("/api/product")
    public Map<String, Object> product() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("product", intentService.activeProduct());
        return result;
    }

    /**
     * 提交购买意向。
     * 入参 {name,phone}（均必填，去空格后非空）；成功返回 {code}——
     * 口令码只在此响应中返回一次，任何其他接口都不再返回。
     */
    @PostMapping("/api/intents")
    public Map<String, Object> submit(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", intentService.submit(str(body.get("name")), str(body.get("phone"))));
        return result;
    }

    /**
     * 凭口令码查询位次与状态。
     * 返回 {intent:{name,phone,status,inTransaction,position,submittedAt}, product}；
     * 口令码无效或已失效 → 404；已进入交易时 position 为 null、inTransaction 为 true。
     */
    @GetMapping("/api/intents/lookup/{code}")
    public Map<String, Object> lookup(@PathVariable String code) {
        return intentService.lookup(code);
    }

    /**
     * 修改姓名与电话。
     * 入参 {name,phone}（均必填，去空格后非空）；修改不改变排队位次；成功返回 {ok:true}。
     */
    @PutMapping("/api/intents/lookup/{code}")
    public Map<String, Object> update(@PathVariable String code, @RequestBody Map<String, Object> body) {
        intentService.update(code, str(body.get("name")), str(body.get("phone")));
        return ok();
    }

    /**
     * 撤销意向。
     * 已进入交易 → 400「你已进入交易，无法自行撤销，请联系卖家」；
     * 其余状态置为已撤销（终态，历史留痕）；成功返回 {ok:true}。
     */
    @PostMapping("/api/intents/lookup/{code}/cancel")
    public Map<String, Object> cancel(@PathVariable String code) {
        intentService.cancel(code);
        return ok();
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
