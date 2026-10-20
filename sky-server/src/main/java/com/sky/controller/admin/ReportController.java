package com.sky.controller.admin;


import com.sky.result.Result;
import com.sky.service.ForecastService;
import com.sky.service.ReportService;
import com.sky.vo.OrderReportVO;
import com.sky.vo.SalesTop10ReportVO;
import com.sky.vo.TurnoverReportVO;
import com.sky.vo.UserReportVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.util.Map;
import java.time.LocalDateTime;

@RestController
@Slf4j
@Api(tags = "数据统计相关接口")
@RequestMapping("/admin/report")
public class ReportController {

    @Autowired
    private ReportService reportService;

    @Autowired
    private ForecastService forecastService;
    @ApiOperation("营业额统计")
    @GetMapping("/turnoverStatistics")
    public Result<TurnoverReportVO> turnoverStatistics(
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate begin,
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end
    ) {
        log.info("统计指定时间的营业额:{},{}",begin,end);
        return Result.success(reportService.getTurnoverStatistics(begin, end));
    }

    @ApiOperation("用户数据统计")
    @GetMapping("userStatistics")
    public Result<UserReportVO> userStatistics(
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate begin,
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end
    ) {
        log.info("统计指定时间的用户:{},{}",begin,end);
        return Result.success(reportService.getUserStatistics(begin, end));
    }

    @ApiOperation("订单数据统计")
    @GetMapping("ordersStatistics")
    public Result<OrderReportVO> ordersStatistics(
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate begin,
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end
    ) {
        log.info("订单数据统计:{},{}",begin,end);
        return Result.success(reportService.getOrderStatistics(begin, end));
    }

//指定时间销量排名
    @ApiOperation("销量排名top10")
    @GetMapping("top10")
    public Result<SalesTop10ReportVO> top10(
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate begin,
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end
    ) {
        log.info("销量排名top10:{},{}",begin,end);
        return Result.success(reportService.getSalesTop10(begin, end));
    }

    /**
     * 导出近 30 天运营数据报表（基于模板填充，行数固定 30 行）
     */
    @ApiOperation("菜品销量预测")
    @GetMapping("/forecast")
    public Result<Map<String, Object>> forecast(Integer days, Integer top) {
        return Result.success(forecastService.forecast(days == null ? 28 : days, top == null ? 10 : top));
    }

    @ApiOperation("导出运营数据报表")
    @GetMapping("/export")
    public void export(HttpServletResponse response) {
        log.info("导出运营数据报表");
        reportService.exportBusinessDate(response);
    }

}














