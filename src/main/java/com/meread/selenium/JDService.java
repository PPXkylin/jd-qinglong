package com.meread.selenium;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.meread.selenium.bean.JDScreenBean;
import com.meread.selenium.bean.MyChrome;
import com.meread.selenium.bean.QLConfig;
import com.meread.selenium.bean.QLToken;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.bytedeco.opencv.opencv_core.Rect;
import org.openqa.selenium.*;
import org.openqa.selenium.html5.LocalStorage;
import org.openqa.selenium.remote.RemoteExecuteMethod;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.html5.RemoteWebStorage;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.Base64Utils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import sun.misc.BASE64Decoder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by yangxg on 2021/9/1
 *
 * @author yangxg
 */
@Service
@Slf4j
public class JDService {

    @Autowired
    private WebDriverFactory driverFactory;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${jd.debug}")
    private boolean isDebug;

    //关闭hub：http://{hubhost}:{hubport}/lifecycle-manager/LifecycleServlet?action=shutdown
    //关闭node：http://localhost:5557/extra/LifecycleServlet?action=shutdown
    //获取所有会话：http://localhost:4444/grid/api/sessions
    //获取hub状态：http://localhost:4444/grid/api/hub/
    //关闭session：http://localhost:5556/wd/hub/session/e0843c23ae1c81e17c87217f973fc503
    //cURL --request DELETE http://localhost:5556/wd/hub/session/e0843c23ae1c81e17c87217f973fc503

//    新的api
    //http://localhost:4444/status 获取hub的状态
    //http://localhost:4444/ui/index.html#/ 后台ui界面

//    cURL --request DELETE 'http://172.18.0.8:5555/se/grid/node/session/a73dade333fd8b68224ca762f087d676' --header 'X-REGISTRATION-SECRET;'
//    cURL --request GET 'http://<node-URL>/se/grid/node/owner/<session-id>' --header 'X-REGISTRATION-SECRET;'

    public String getJDCookies(String chromeSessionId) {
        String mockCookie = System.getenv("mockCookie");
        if ("1".equals(mockCookie)) {
            String uuid = UUID.randomUUID().toString().replace("-", "");
            return "pt_key=test_" + uuid + ";pt_pin=" + uuid;
        }
        StringBuilder sb = new StringBuilder();
        RemoteWebDriver webDriver = driverFactory.getDriverBySessionId(chromeSessionId);
        if (webDriver != null) {
            String currentUrl = webDriver.getCurrentUrl();
            log.info("getJDCookies " + chromeSessionId + "," + currentUrl);
            if (!currentUrl.startsWith("data:")) {
                Set<Cookie> cookies = webDriver.manage().getCookies();
                for (Cookie cookie : cookies) {
                    if ("pt_key".equals(cookie.getName())) {
                        sb.append("pt_key=").append(cookie.getValue()).append(";");
                        break;
                    }
                }
                for (Cookie cookie : cookies) {
                    if ("pt_pin".equals(cookie.getName())) {
                        sb.append("pt_pin=").append(cookie.getValue()).append(";");
                        break;
                    }
                }
            }
        }
        return sb.toString();
    }

    static Pattern pattern = Pattern.compile("data:image.*base64,(.*)");

    private JDScreenBean getScreenInner(String sessionId) throws IOException, InterruptedException {
        RemoteWebDriver webDriver = driverFactory.getDriverBySessionId(sessionId);

        String screenBase64 = null;
        byte[] screen = null;
        if (!"docker".equals(activeProfile)) {
            //创建全屏截图
            screen = ((TakesScreenshot) webDriver).getScreenshotAs(OutputType.BYTES);
            screenBase64 = Base64Utils.encodeToString(screen);
        }

        //是否空白页面
        String currentUrl = webDriver.getCurrentUrl();
        if (currentUrl.startsWith("data:")) {
            return new JDScreenBean(screenBase64, JDScreenBean.PageStatus.EMPTY_URL);
        }

        //获取网页文字
        String pageText = null;
        try {
            pageText = webDriver.findElement(By.tagName("body")).getText();
        } catch (Exception e) {
            return new JDScreenBean(screenBase64, JDScreenBean.PageStatus.EMPTY_URL);
        }

        WebElement element = null;
        if (pageText.contains("京东登录注册")) {
            element = webDriver.findElement(By.id("app"));
        }

        String jdCookies = getJDCookies(sessionId);
        if (element == null && StringUtils.isEmpty(jdCookies)) {
            return new JDScreenBean(screenBase64, JDScreenBean.PageStatus.EMPTY_URL);
        }

        if (!StringUtils.isEmpty(jdCookies)) {
            return new JDScreenBean(screenBase64, JDScreenBean.PageStatus.SUCCESS_CK, jdCookies);
        }

        if (pageText.contains("短信验证码发送次数")) {
            return new JDScreenBean(screenBase64, JDScreenBean.PageStatus.VERIFY_CODE_MAX);
        }

        if (pageText.contains("短信验证码登录")) {
            return new JDScreenBean(screenBase64, JDScreenBean.PageStatus.SWITCH_SMS_LOGIN);
        }

        WebElement loginBtn = webDriver.findElement(By.xpath("//a[@report-eventid='MLoginRegister_SMSLogin']"));
        HashSet<String> loginBtnClasses = new HashSet<>(Arrays.asList(loginBtn.getAttribute("class").split(" ")));
        WebElement sendAuthCodeBtn = webDriver.findElement(By.xpath("//button[@report-eventid='MLoginRegister_SMSReceiveCode']"));
        HashSet<String> sendAuthCodeBtnClasses = new HashSet<>(Arrays.asList(sendAuthCodeBtn.getAttribute("class").split(" ")));
        //登录按钮是否可点击
        boolean canClickLogin = loginBtnClasses.contains("btn-active");
        //获取验证码是否可点击
        boolean canSendAuth = sendAuthCodeBtnClasses.contains("active");
        int authCodeCountDown = -1;
        if (!canSendAuth) {
            String timerText = sendAuthCodeBtn.getText().trim();
            if ("获取验证码".equals(timerText)) {
                authCodeCountDown = 0;
            } else {
                String regex = "重新获取\\((\\d+)s\\)";
                Pattern compile = Pattern.compile(regex);
                Matcher matcher = compile.matcher(timerText);
                if (matcher.matches()) {
                    String group = matcher.group(1);
                    authCodeCountDown = Integer.parseInt(group);
                }
            }
        }

        WebElement chapter_element;
        //需要输入验证码
        if (pageText.contains("安全验证") && !pageText.contains("验证成功")) {
            //创建全屏截图
            screen = ((TakesScreenshot) webDriver).getScreenshotAs(OutputType.BYTES);
            screenBase64 = Base64Utils.encodeToString(screen);
            chapter_element = webDriver.findElement(By.id("captcha_modal"));
            if (chapter_element != null) {
                element = chapter_element;
                //截取某个元素的图，此处为了方便调试和验证，所以如果出现验证码 就只获取验证码的截图
                BufferedImage subImg = null;
                Rectangle rect = element.getRect();
                int x = rect.x;
                int y = rect.y;
                int w = rect.width;
                int h = rect.height;
                subImg = ImageIO.read(new ByteArrayInputStream(screen)).getSubimage(x, y, w, h);
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                ImageIO.write(subImg, "png", outputStream);
                screen = outputStream.toByteArray();
                screenBase64 = Base64Utils.encodeToString(screen);
                return new JDScreenBean(screenBase64, JDScreenBean.PageStatus.REQUIRE_VERIFY);
            }
        }

        if (pageText.contains("验证码错误多次")) {
            return new JDScreenBean(screenBase64, JDScreenBean.PageStatus.VERIFY_FAILED_MAX);
        }

        Long expire = redisTemplate.getExpire(WebDriverFactory.CLIENT_SESSION_ID_KEY + ":" + sessionId);
        JDScreenBean bean = new JDScreenBean(screenBase64, jdCookies, JDScreenBean.PageStatus.NORMAL, authCodeCountDown, canClickLogin, canSendAuth, expire, 0);
        if (!StringUtils.isEmpty(jdCookies)) {
            bean.setPageStatus(JDScreenBean.PageStatus.SUCCESS_CK);
        }

        return bean;
    }

    public void crackCaptcha(String sessionId) throws IOException {
        RemoteWebDriver webDriver = driverFactory.getDriverBySessionId(sessionId);
        WebElement img_tips_wraper = webDriver.findElement(By.xpath("//div[@class='img_tips_wraper']"));
        if (!img_tips_wraper.isDisplayed()) {
            String cpc_img = webDriver.findElement(By.id("cpc_img")).getAttribute("src");
            String small_img = webDriver.findElement(By.id("small_img")).getAttribute("src");

            Matcher matcher = pattern.matcher(cpc_img);
            String bigImageBase64 = null;
            String smallImageBase64 = null;
            if (matcher.matches()) {
                bigImageBase64 = matcher.group(1);
            }
            matcher = pattern.matcher(small_img);
            if (matcher.matches()) {
                smallImageBase64 = matcher.group(1);
            }
            if (bigImageBase64 != null && smallImageBase64 != null) {
                BASE64Decoder decoder = new BASE64Decoder();
                byte[] bgBytes = decoder.decodeBuffer(bigImageBase64);
                byte[] bgSmallBytes = decoder.decodeBuffer(smallImageBase64);
                UUID uuid = UUID.randomUUID();
                File file1 = new File(uuid + "_captcha.jpg");
                File file2 = new File(uuid + "_captcha_small.jpg");
                FileUtils.writeByteArrayToFile(file1, bgBytes);
                FileUtils.writeByteArrayToFile(file2, bgSmallBytes);
                Rect rect = OpenCVUtil.getOffsetX(file1.getAbsolutePath(), file2.getAbsolutePath());

                if (isDebug) {
                    String markedJpg = "data:image/jpg;base64," + Base64Utils.encodeToString(FileUtils.readFileToByteArray(new File(uuid + "_captcha.origin.marked.jpeg")));
                    webDriver.executeScript("document.getElementById('cpc_img').setAttribute('src','" + markedJpg + "')");
                    FileUtils.writeByteArrayToFile(new File(uuid + "_captcha_" + rect.x() + ".jpg"), bgBytes);
                }

                WebElement slider = webDriver.findElement(By.xpath("//div[@class='sp_msg']/img"));
//                SlideVerifyBlock.moveWay2(webDriver, slider, rect.x(), uuid.toString(),isDebug);
                SlideVerifyBlock.moveWay1(webDriver, slider, rect.x());
                FileUtils.deleteQuietly(file1);
                FileUtils.deleteQuietly(file2);
            }
        }
    }

    public void toJDlogin(String sessionId) {
        RemoteWebDriver webDriver = driverFactory.getDriverBySessionId(sessionId);
        webDriver.manage().deleteAllCookies();
        try {
            webDriver.navigate().to("https://plogin.m.jd.com/login/login?appid=300&returnurl=https%3A%2F%2Fwq.jd.com%2Fpassport%2FLoginRedirect%3Fstate%3D1101624461975%26returnurl%3Dhttps%253A%252F%252Fhome.m.jd.com%252FmyJd%252Fnewhome.action%253Fsceneval%253D2%2526ufc%253D%2526&source=wq_passport");
        } catch (Exception e) {
            e.printStackTrace();
        }
        webDriver.navigate().to("https://plogin.m.jd.com/login/login?appid=300&returnurl=https%3A%2F%2Fwq.jd.com%2Fpassport%2FLoginRedirect%3Fstate%3D1101624461975%26returnurl%3Dhttps%253A%252F%252Fhome.m.jd.com%252FmyJd%252Fnewhome.action%253Fsceneval%253D2%2526ufc%253D%2526&source=wq_passport");
        WebDriverUtil.waitForJStoLoad(webDriver);
    }

    public void controlChrome(String sessionId, String currId, String currValue) {
        RemoteWebDriver webDriver = driverFactory.getDriverBySessionId(sessionId);
        WebElement element = null;
        if ("phone".equals(currId)) {
            element = webDriver.findElement(By.xpath("//input[@type='tel']"));
        } else if ("sms_code".equals(currId)) {
            element = webDriver.findElement(By.id("authcode"));
        }
        if (element != null) {
            element.sendKeys(Keys.CONTROL + "a");
            element.sendKeys(currValue);
        }
    }

    public void jdLogin(String sessionId) throws IOException, InterruptedException {
        RemoteWebDriver webDriver = driverFactory.getDriverBySessionId(sessionId);
        JDScreenBean screen = getScreen(sessionId);
        if (screen.isCanClickLogin()) {
            WebDriverWait wait = new WebDriverWait(webDriver, 5);
            WebElement element = null;
            try {
                element = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//a[@report-eventid='MLoginRegister_SMSLogin']")));
                element.click();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        WebDriverUtil.waitForJStoLoad(webDriver);
    }

    public void reset(String sessionId) {
        RemoteWebDriver webDriver = driverFactory.getDriverBySessionId(sessionId);
        webDriver.manage().deleteAllCookies();
        webDriver.navigate().to("https://plogin.m.jd.com/login/login?appid=300&returnurl=https%3A%2F%2Fwq.jd.com%2Fpassport%2FLoginRedirect%3Fstate%3D1101624461975%26returnurl%3Dhttps%253A%252F%252Fhome.m.jd.com%252FmyJd%252Fnewhome.action%253Fsceneval%253D2%2526ufc%253D%2526&source=wq_passport");
        WebDriverUtil.waitForJStoLoad(webDriver);
    }

    public void click(String sessionId, By xpath) {
        RemoteWebDriver webDriver = driverFactory.getDriverBySessionId(sessionId);
        WebElement sureButton = webDriver.findElement(xpath);
        sureButton.click();
    }

    public boolean sendAuthCode(String sessionId) throws IOException, InterruptedException {
        RemoteWebDriver webDriver = driverFactory.getDriverBySessionId(sessionId);
        JDScreenBean screen = getScreen(sessionId);
        if (screen.isCanSendAuth()) {
            WebElement sendAuthCodeBtn = webDriver.findElement(By.xpath("//button[@report-eventid='MLoginRegister_SMSReceiveCode']"));
            sendAuthCodeBtn.click();
            return true;
        } else {
            return false;
        }
    }

    public JDScreenBean getScreen(String sessionId) {
        JDScreenBean bean = null;
        try {
            bean = getScreenInner(sessionId);
            if (bean.getPageStatus() == JDScreenBean.PageStatus.EMPTY_URL) {
                toJDlogin(sessionId);
//            } else if (bean.getPageStatus() == JDScreenBean.PageStatus.REQUIRE_VERIFY) {
//                service.crackCaptcha();
            } else if (bean.getPageStatus() == JDScreenBean.PageStatus.VERIFY_FAILED_MAX) {
                click(sessionId, By.xpath("//div[@class='alert-sure']"));
            } else if (bean.getPageStatus() == JDScreenBean.PageStatus.VERIFY_CODE_MAX) {
                click(sessionId, By.xpath("//button[@class='dialog-sure']"));
            } else if (bean.getPageStatus() == JDScreenBean.PageStatus.SWITCH_SMS_LOGIN) {
                click(sessionId, By.xpath("//span[@report-eventid='MLoginRegister_SMSVerification']"));
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return null;
        }
        Long expire = redisTemplate.getExpire(WebDriverFactory.CLIENT_SESSION_ID_KEY + ":" + sessionId);
        bean.setSessionTimeOut(expire);
        List<MyChrome> chromes = driverFactory.getChromes();
        int availChrome = 0;
        for (MyChrome chrome : chromes) {
            String cookie = chrome.getClientSessionId();
            if (cookie == null) {
                availChrome++;
            }
        }
        bean.setAvailChrome(availChrome);
        return bean;
    }

    public int uploadQingLong(String sessionId, String ck, String remark, QLConfig qlConfig) {
        RemoteWebDriver webDriver = driverFactory.getDriverBySessionId(sessionId);
        webDriver.get(qlConfig.getQlUrl() + "/login");
        boolean b = WebDriverUtil.waitForJStoLoad(webDriver);
        if (b) {
            webDriver.findElement(By.id("username")).sendKeys(qlConfig.getQlUsername());
            webDriver.findElement(By.id("password")).sendKeys(qlConfig.getQlPassword());
            webDriver.findElement(By.xpath("//button[@type='submit']")).click();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            b = WebDriverUtil.waitForJStoLoad(webDriver);
            if (b) {
                RemoteExecuteMethod executeMethod = new RemoteExecuteMethod(webDriver);
                RemoteWebStorage webStorage = new RemoteWebStorage(executeMethod);
                LocalStorage storage = webStorage.getLocalStorage();
                String token = storage.getItem("token");
                if (token != null) {
                    QLConfig tmpConfig = new QLConfig();
                    BeanUtils.copyProperties(qlConfig, tmpConfig);
                    tmpConfig.setQlToken(new QLToken(token));
                    return uploadQingLongWithToken(ck, remark, tmpConfig);
                } else {
                    return 0;
                }
            }
        }
        return -1;
    }

    public int uploadQingLongWithToken(String ck, String remark, QLConfig qlConfig) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + qlConfig.getQlToken().getToken());
        headers.add("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/93.0.4577.63 Safari/537.36");
        headers.add("Content-Type", "application/json;charset=UTF-8");
        headers.add("Accept-Encoding", "gzip, deflate");
        headers.add("Accept-Language", "zh-CN,zh;q=0.9");

        String url = qlConfig.getQlUrl() + "/" + (qlConfig.getQlLoginType() == QLConfig.QLLoginType.TOKEN ? "open" : "api") + "/envs?searchValue=" + remark + "&t=" + System.currentTimeMillis();
        log.info("开始获取当前ck列表" + url);
        ResponseEntity<String> exchange = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        boolean update = false;
        String updateId = "";
        if (exchange.getStatusCode().is2xxSuccessful()) {
            String body = exchange.getBody();
            log.info("body = " + body);
            JSONArray data = JSON.parseObject(body).getJSONArray("data");
            if (data != null && data.size() > 0) {
                for (int i = 0; i < data.size(); i++) {
                    JSONObject jsonObject = data.getJSONObject(i);
                    String remarks = jsonObject.getString("remarks");
                    String _id = jsonObject.getString("_id");
                    if (!StringUtils.isEmpty(remark) && remark.equals(remarks)) {
                        update = true;
                        updateId = _id;
                        break;
                    }
                }
            }
        }

        url = qlConfig.getQlUrl() + "/" + (qlConfig.getQlLoginType() == QLConfig.QLLoginType.TOKEN ? "open" : "api") + "/envs?t=" + System.currentTimeMillis();
        if (!update) {
            JSONArray jsonArray = new JSONArray();
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("value", ck);
            jsonObject.put("name", "JD_COOKIE");
            if (ck != null) {
                jsonObject.put("remarks", remark);
            }
            jsonArray.add(jsonObject);
            HttpEntity<?> request = new HttpEntity<>(jsonArray.toJSONString(), headers);
            log.info("开始上传ck" + url);
            exchange = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
            if (exchange.getStatusCode().is2xxSuccessful()) {
                log.info("create resp content : " + exchange.getBody() + ", resp code : " + exchange.getStatusCode());
                return 1;
            }
        } else {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("value", ck);
            jsonObject.put("name", "JD_COOKIE");
            if (ck != null) {
                jsonObject.put("remarks", remark);
            }
            jsonObject.put("_id", updateId);
            HttpEntity<?> request = new HttpEntity<>(jsonObject.toJSONString(), headers);
            log.info("开始更新ck" + url);
            exchange = restTemplate.exchange(url, HttpMethod.PUT, request, String.class);
            if (exchange.getStatusCode().is2xxSuccessful()) {
                log.info("update resp content : " + exchange.getBody() + ", resp code : " + exchange.getStatusCode());
                return 1;
            }
        }
        return -1;
    }

}
