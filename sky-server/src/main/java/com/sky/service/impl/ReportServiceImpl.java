package com.sky.service.impl;

import com.sky.dto.GoodsSalesDTO;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.service.WorkspaceService;
import com.sky.vo.BusinessDataVO;
import com.sky.vo.OrderReportVO;
import com.sky.vo.SalesTop10ReportVO;
import com.sky.vo.TurnoverReportVO;
import com.sky.vo.UserReportVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ReportServiceImpl implements ReportService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WorkspaceService workspaceService;

    @Override/*
    统计指定时间的营业额
    */
    public TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end) {
        //当前集合存放从begin到end的日期
        ArrayList<LocalDate> dateList = new ArrayList<>();

        dateList.add(begin);

        while (!begin.equals(end)) {
            begin = begin.plusDays(1);
            dateList.add(begin);
        }

        //一次聚合查询替代"每天查一次"：30 天从 30 条 SQL 降为 1 条
        LocalDateTime beginTime = LocalDateTime.of(dateList.get(0), LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(dateList.get(dateList.size() - 1), LocalTime.MAX);
        Map<String, Double> turnoverMap = toDoubleMap(orderMapper.sumTurnoverByDay(beginTime, endTime));

        List<Double> turnoverList = new ArrayList<Double>();
        for (LocalDate date : dateList) {
            turnoverList.add(turnoverMap.getOrDefault(date.toString(), 0.0));
        }
        return TurnoverReportVO.builder()
                .dateList(StringUtils.join(dateList, ","))
                .turnoverList(StringUtils.join(turnoverList, ","))
                .build();
    }

    private Map<String, Double> toDoubleMap(List<Map<String, Object>> rows) {
        Map<String, Double> result = new HashMap<>();
        if (rows == null) {
            return result;
        }
        for (Map<String, Object> row : rows) {
            Object day = row.get("d");
            Object value = row.get("v");
            if (day == null) {
                continue;
            }
            result.put(day.toString(), value == null ? 0.0 : ((Number) value).doubleValue());
        }
        return result;
    }

    private Map<String, Integer> toIntMap(List<Map<String, Object>> rows) {
        Map<String, Integer> result = new HashMap<>();
        if (rows == null) {
            return result;
        }
        for (Map<String, Object> row : rows) {
            Object day = row.get("d");
            Object value = row.get("v");
            if (day == null) {
                continue;
            }
            result.put(day.toString(), value == null ? 0 : ((Number) value).intValue());
        }
        return result;
    }

    @Override
    public UserReportVO getUserStatistics(LocalDate begin, LocalDate end) {
        //当前集合存放从begin到end的日期
        ArrayList<LocalDate> dateList = new ArrayList<>();

        dateList.add(begin);

        while (!begin.equals(end)) {
            begin = begin.plusDays(1);
            dateList.add(begin);
        }

        LocalDateTime beginTime = LocalDateTime.of(dateList.get(0), LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(dateList.get(dateList.size() - 1), LocalTime.MAX);

        //新增用户一次聚合；累计用户用"基线 + 逐日累加"，避免每天再查一次总数
        Map<String, Integer> newUserMap = toIntMap(userMapper.countNewUsersByDay(beginTime, endTime));
        Integer historyTotal = userMapper.countUsersBefore(beginTime);
        int cumulative = historyTotal == null ? 0 : historyTotal;

        List<Integer> newUserList = new ArrayList<>();
        List<Integer> totalUserList = new ArrayList<>();
        for (LocalDate date : dateList) {
            int newUsers = newUserMap.getOrDefault(date.toString(), 0);
            cumulative += newUsers;
            newUserList.add(newUsers);
            totalUserList.add(cumulative);
        }
        return UserReportVO.builder()
                .dateList(StringUtils.join(dateList, ","))
                .totalUserList(StringUtils.join(totalUserList, ","))
                .newUserList(StringUtils.join(newUserList, ","))
                .build();
    }

    /*
     * 订单数据统计
     */
    @Override
    public OrderReportVO getOrderStatistics(LocalDate begin, LocalDate end) {
        //当前集合存放从begin到end的日期
        ArrayList<LocalDate> dateList = new ArrayList<>();
        dateList.add(begin);
        while (!begin.equals(end)) {
            begin = begin.plusDays(1);
            dateList.add(begin);
        }

        LocalDateTime beginTime = LocalDateTime.of(dateList.get(0), LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(dateList.get(dateList.size() - 1), LocalTime.MAX);

        //两次聚合查询分别拿到"全部订单"和"有效订单"，替代原来每天 2 条 SQL
        Map<String, Integer> allOrderMap = toIntMap(orderMapper.countOrdersByDay(beginTime, endTime, null));
        Map<String, Integer> validOrderMap = toIntMap(
                orderMapper.countOrdersByDay(beginTime, endTime, Orders.COMPLETED));

        List<Integer> orderCountList = new ArrayList<>();
        List<Integer> validOrderCountList = new ArrayList<>();
        int totalOrderCount = 0;
        int validOrderCount = 0;
        for (LocalDate date : dateList) {
            int orderCount = allOrderMap.getOrDefault(date.toString(), 0);
            int validCount = validOrderMap.getOrDefault(date.toString(), 0);
            orderCountList.add(orderCount);
            validOrderCountList.add(validCount);
            totalOrderCount += orderCount;
            validOrderCount += validCount;
        }

        //计算订单完成率，避免总订单数为0时除零
        double orderCompletionRate = totalOrderCount == 0
                ? 0.0
                : (double) validOrderCount / totalOrderCount;

        return OrderReportVO.builder()
                .dateList(StringUtils.join(dateList, ","))
                .orderCountList(StringUtils.join(orderCountList, ","))
                .validOrderCountList(StringUtils.join(validOrderCountList, ","))
                .totalOrderCount(totalOrderCount)
                .validOrderCount(validOrderCount)
                .orderCompletionRate(orderCompletionRate)
                .build();
    }

    /*销量统计*/
    @Override
    public SalesTop10ReportVO getSalesTop10(LocalDate begin, LocalDate end) {

        LocalDateTime beginTime = LocalDateTime.of(begin, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(end, LocalTime.MAX);

        List<GoodsSalesDTO> salesTop10 = orderMapper.getSalesTop10(beginTime, endTime);
        List<String> names = salesTop10.stream().map(GoodsSalesDTO::getName).collect(Collectors.toList());
        String nameList = StringUtils.join(names, ",");

        List<Integer> numbers = salesTop10.stream().map(GoodsSalesDTO::getNumber).collect(Collectors.toList());
        String numberList = StringUtils.join(numbers, ",");

        return SalesTop10ReportVO.builder()
                .nameList(nameList)
                .numberList(numberList)
                .build();
    }

    //导出报表数据
    @Override
    public void exportBusinessDate(HttpServletResponse response) {
        LocalDate begin = LocalDate.now().minusDays(30);
        LocalDate end = LocalDate.now().minusDays(1);

        LocalDateTime beginTime = LocalDateTime.of(begin, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(end, LocalTime.MAX);
        BusinessDataVO businessData = workspaceService.getBusinessData(beginTime, endTime);
        TurnoverReportVO turnoverReport = getTurnoverStatistics(begin, end);
        OrderReportVO orderReport = getOrderStatistics(begin, end);
        UserReportVO userReport = getUserStatistics(begin, end);

        String[] turnoverList = turnoverReport.getTurnoverList().split(",");
        String[] orderCountList = orderReport.getOrderCountList().split(",");
        String[] validOrderCountList = orderReport.getValidOrderCountList().split(",");
        String[] newUserList = userReport.getNewUserList().split(",");

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");

        try (InputStream inputStream = getClass().getClassLoader()
                .getResourceAsStream("运营数据报表模板.xlsx")) {
            if (inputStream == null) {
                throw new IllegalStateException("找不到运营数据报表模板");
            }

            try (Workbook workbook = new XSSFWorkbook(inputStream)) {
                Sheet sheet = workbook.getSheetAt(0);

                //填充日期范围
                sheet.getRow(1).getCell(1).setCellValue(begin + "至" + end);

                //填充概览数据
                sheet.getRow(3).getCell(2).setCellValue(valueOrZero(businessData.getTurnover()));
                sheet.getRow(3).getCell(4).setCellValue(valueOrZero(businessData.getOrderCompletionRate()));
                sheet.getRow(3).getCell(6).setCellValue(valueOrZero(businessData.getNewUsers()));
                sheet.getRow(4).getCell(2).setCellValue(valueOrZero(businessData.getValidOrderCount()));
                sheet.getRow(4).getCell(4).setCellValue(valueOrZero(businessData.getUnitPrice()));

                //填充明细数据
                for (int i = 0; i < 30; i++) {
                    Row row = sheet.getRow(7 + i);
                    double turnover = Double.parseDouble(turnoverList[i]);
                    int orderCount = Integer.parseInt(orderCountList[i]);
                    int validOrderCount = Integer.parseInt(validOrderCountList[i]);
                    double orderCompletionRate = orderCount == 0
                            ? 0.0 : (double) validOrderCount / orderCount;
                    double unitPrice = validOrderCount == 0
                            ? 0.0 : turnover / validOrderCount;

                    row.getCell(1).setCellValue(begin.plusDays(i).toString());
                    row.getCell(2).setCellValue(turnover);
                    row.getCell(3).setCellValue(validOrderCount);
                    row.getCell(4).setCellValue(orderCompletionRate);
                    row.getCell(5).setCellValue(unitPrice);
                    row.getCell(6).setCellValue(Integer.parseInt(newUserList[i]));
                }

                String fileName = URLEncoder.encode("运营数据报表.xlsx", "UTF-8");
                response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + fileName);
                workbook.write(response.getOutputStream());
                response.flushBuffer();
            }
        } catch (IOException e) {
            log.error("导出运营数据报表失败", e);
            throw new RuntimeException("导出运营数据报表失败", e);
        }
    }

    private double valueOrZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private double valueOrZero(Integer value) {
        return value == null ? 0.0 : value;
    }
}































