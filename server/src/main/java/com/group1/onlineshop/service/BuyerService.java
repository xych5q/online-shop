package com.group1.onlineshop.service;

import com.group1.onlineshop.entity.Buyer;
import com.group1.onlineshop.entity.Intent;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.exception.BizException;
import com.group1.onlineshop.repository.BuyerRepository;
import com.group1.onlineshop.repository.IntentRepository;
import com.group1.onlineshop.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 买家账号服务（挑战需求 A · FR-D1 / FR-D2 / FR-D4，任务 D-1 / D-2 / D-4）。
 *
 * <p>规则依据（逐条来自 [[需求规格说明书]] v1.5 第 8 章 与 [[需求A的需求问题库]]）：
 * <ul>
 *   <li>FR-D1 / A.2.1-11 用户名 3–20 位，只允许字母、数字、下划线；<b>唯一性校验忽略大小写</b>，显示保留原样</li>
 *   <li>FR-D1 / A.2.1-6 密码 ≥8 位且至少含字母与数字（强度细则由本组自定），BCrypt 加盐哈希存储</li>
 *   <li>FR-D1 / A.2.1-3 联系电话必填、<b>不唯一</b>；A.2.1-7 无手机验证码</li>
 *   <li>附三缓解措施：注册时手机号<b>二次确认输入</b>，不一致直接拒绝（低成本防填错，R-C）</li>
 *   <li>FR-D1 / A.2.1-4、A.2.1-5 默认交易地点选填、自由文本、只填一个、不参与任何交易校验</li>
 *   <li>FR-D2 / A.2.2 登录与退出；登录态凭证为 token，登录后刷新、退出即失效（沿用 Seller 的做法）</li>
 *   <li>FR-D4 / A.2.2-1~A.2.2-8 我的历史：商品名称、下单时间、交易结果（三值）、交易时间；
 *       按提交时间倒序、10 条/页、<b>不含价格与图片</b>、只读</li>
 *   <li>A.2.1-8、A.2.1-9 <b>不做</b>修改资料、找回/重置密码、注销账号（故本服务不提供这些入口）</li>
 * </ul>
 *
 * <p>边界说明：本服务只读写 {@link Buyer}、只读 {@link Intent} 与 {@link Product}，
 * 不改动任何实体与仓储（entity/、repository/ 归陈星宇）。
 */
@Service
@RequiredArgsConstructor
public class BuyerService {

    /** 用户名：3–20 位字母 / 数字 / 下划线（A.2.1-11，本组自定字符集） */
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,20}$");

    /** 默认交易地点最大长度（与 Buyer.defaultLocation 的 length=200 对齐） */
    private static final int MAX_LOCATION = 200;

    private final BuyerRepository buyerRepository;
    private final IntentRepository intentRepository;
    private final ProductRepository productRepository;

    /** BCrypt 编码器：注册时编码、登录时校验（BCrypt 自带随机盐，A.2.1-6） */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 登录态 token 随机源 */
    private final SecureRandom random = new SecureRandom();

    /** 我的历史每页条数（application.yml: app.history.page-size = 10，FR-D4） */
    @Value("${app.history.page-size:10}")
    private int historyPageSize;

    /**
     * 买家注册。
     *
     * @param username        用户名（3–20 位字母数字下划线，忽略大小写唯一）
     * @param password        密码（≥8 位且同时含字母与数字）
     * @param phone           联系电话（必填，不唯一）
     * @param phoneConfirm    联系电话二次确认（附三缓解措施，必须与 phone 一致）
     * @param defaultLocation 默认交易地点（选填，自由文本，不参与交易校验）
     * @return {ok:true, buyer:{id,username,phone,defaultLocation,createdAt}}
     */
    @Transactional
    public Map<String, Object> register(String username, String password, String phone,
                                        String phoneConfirm, String defaultLocation) {
        String uname = text(username);
        String pwd = password == null ? "" : password;
        String tel = text(phone);
        String tel2 = text(phoneConfirm);
        String location = text(defaultLocation);

        if (uname.isEmpty()) {
            throw new BizException("用户名不能为空");
        }
        if (!USERNAME.matcher(uname).matches()) {
            throw new BizException("用户名需为 3-20 位字母、数字或下划线");
        }
        if (buyerRepository.existsByUsernameIgnoreCase(uname)) {
            throw new BizException("用户名已被注册");
        }
        if (pwd.length() < 8 || !pwd.chars().anyMatch(Character::isLetter)
                || !pwd.chars().anyMatch(Character::isDigit)) {
            throw new BizException("密码长度不能少于 8 位，且需同时包含字母与数字");
        }
        if (tel.isEmpty()) {
            throw new BizException("联系电话不能为空");
        }
        if (!tel.equals(tel2)) {
            throw new BizException("两次输入的联系电话不一致");
        }
        if (location.length() > MAX_LOCATION) {
            throw new BizException("默认交易地点不能超过 200 字");
        }

        Buyer buyer = new Buyer();
        buyer.setUsername(uname);
        buyer.setPasswordHash(passwordEncoder.encode(pwd));
        buyer.setPhone(tel);
        buyer.setDefaultLocation(location.isEmpty() ? null : location);
        buyer.setCreatedAt(LocalDateTime.now());
        buyer.setToken(null);
        buyerRepository.save(buyer);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("buyer", buyerJson(buyer));
        return result;
    }

    /**
     * 买家登录。
     * 用户名或密码错误统一返回 401「用户名或密码错误」（不区分哪一项错，与卖家登录一致）；
     * 成功后刷新 token 并持久化，旧 token 立即失效。
     *
     * @return {token, username}
     */
    @Transactional
    public Map<String, Object> login(String username, String password) {
        String uname = text(username);
        String pwd = password == null ? "" : password;
        Buyer buyer = buyerRepository.findByUsernameIgnoreCase(uname)
                .orElseThrow(() -> new BizException("用户名或密码错误", 401));
        if (!passwordEncoder.matches(pwd, buyer.getPasswordHash())) {
            throw new BizException("用户名或密码错误", 401);
        }
        buyer.setToken(newToken());
        buyerRepository.save(buyer);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", buyer.getToken());
        result.put("username", buyer.getUsername());
        return result;
    }

    /** 退出登录：清空 token，旧凭证立即失效（FR-D2）。token 无效时按未登录处理抛 401。 */
    @Transactional
    public void logout(String token) {
        Buyer buyer = requireAuth(token);
        buyer.setToken(null);
        buyerRepository.save(buyer);
    }

    /**
     * 当前登录买家（FR-D2 / D-2）。
     *
     * @return {buyer:{id,username,phone,defaultLocation,createdAt}}
     */
    public Map<String, Object> me(String token) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("buyer", buyerJson(requireAuth(token)));
        return result;
    }

    /**
     * 我的历史（FR-D4 / D-4）：只取当前登录账号名下的意向，按提交时间倒序、10 条/页。
     *
     * <p>出参 {page,totalPages,total,items[]}，每条含：商品名称、下单时间、交易结果（三值）、交易时间。
     * <b>不含价格与图片</b>（A.2.2-4）；旧系统归档数据 buyerId 为 null，自然不会出现（A.2.4-3）。
     *
     * <p><b>ARCHIVED 在查询层排除</b>（PR #2 评审意见）：分页的 total / totalPages 直接由
     * {@code findByBuyerIdAndStatusNot...} 得出，不再「查出来再 continue」——否则归档记录会
     * 被计入 total，导致末页出现空列表、页码数偏大。
     *
     * @param page 页码，从 1 起（与 SellerService.history 的口径一致）
     */
    public Map<String, Object> history(String token, Integer page) {
        Buyer buyer = requireAuth(token);
        int cur = page == null || page < 1 ? 1 : page;
        Page<Intent> p = intentRepository.findByBuyerIdAndStatusNotOrderBySubmittedAtDescIdDesc(
                buyer.getId(), Intent.Status.ARCHIVED, PageRequest.of(cur - 1, historyPageSize));

        List<Map<String, Object>> items = new ArrayList<>();
        for (Intent it : p.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("intentId", it.getId());
            m.put("productId", it.getProductId());
            m.put("productName", productName(it.getProductId()));
            m.put("submittedAt", it.getSubmittedAt());
            m.put("result", resultText(it.getStatus()));
            m.put("dealTime", dealTime(it));
            items.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page", cur);
        result.put("totalPages", p.getTotalPages());
        result.put("total", p.getTotalElements());
        result.put("items", items);
        return result;
    }

    /**
     * 登录态校验：供本服务与外部域（如提交意向时解析买家）使用。
     * token 为空或查不到账号 → 401「未登录或登录已失效」（接口设计 v2 §1.2）。
     */
    public Buyer requireAuth(String token) {
        String t = token == null ? "" : token.trim();
        if (t.isEmpty()) {
            throw new BizException("未登录或登录已失效", 401);
        }
        return buyerRepository.findByToken(t)
                .orElseThrow(() -> new BizException("未登录或登录已失效", 401));
    }

    /** 买家侧「我的历史」的交易结果三值（A.2.2-8）：待交易 / 成功 / 失败 */
    private String resultText(Intent.Status status) {
        if (status == null) {
            return "待交易";
        }
        switch (status) {
            case QUEUED:
            case IN_TRANSACTION:
                return "待交易";
            case SUCCESS:
                return "成功";
            default: // FAILED / SOLD_OUT（售罄失效）/ CANCELLED（旧数据）
                return "失败";
        }
    }

    /**
     * 交易时间（PR #2 评审意见第 2 条，口径已与陈星宇对齐）：
     * 直接取 {@code Intent.dealtAt}——该字段由陈星宇 C-1 落地，并在
     * {@code TradeService#markResult}（W-1 / W-5 / W-6 所在域）标记交易结果时写入；
     * 排队中、交易中一律为 null。
     *
     * <p><b>历史变更</b>：PR #2 时 {@code Intent} 尚无成交时间列，本方法临时回查
     * {@code Product.closedAt} 代替，并留下 TODO。C-1 落地后（develop 提交 7a8bdb5）
     * 本方法已改为直接读 {@code dealtAt}，不再回查商品表、不再依赖下架时间，
     * 历史列表的「交易时间」从此是真成交时间而非商品下架时间。
     */
    private LocalDateTime dealTime(Intent intent) {
        if (intent.getStatus() == Intent.Status.QUEUED || intent.getStatus() == Intent.Status.IN_TRANSACTION) {
            return null;
        }
        return intent.getDealtAt();
    }

    /** 商品名称；商品已被删除时给空串，避免历史列表整条报错 */
    private String productName(Long productId) {
        return productRepository.findById(productId).map(Product::getName).orElse("");
    }

    /** 买家对外字段：不返回密码哈希（passwordHash） */
    private Map<String, Object> buyerJson(Buyer buyer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", buyer.getId());
        m.put("username", buyer.getUsername());
        m.put("phone", buyer.getPhone());
        m.put("defaultLocation", buyer.getDefaultLocation());
        m.put("createdAt", buyer.getCreatedAt());
        return m;
    }

    /** 生成 48 位十六进制随机 token（与 SellerService 的做法一致） */
    private String newToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** null 安全且去空格的字符串转换 */
    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
