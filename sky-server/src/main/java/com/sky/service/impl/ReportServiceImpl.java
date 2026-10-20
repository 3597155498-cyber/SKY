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
        //存放每天营业额
        List<Double> turnoverList = new ArrayList<Double>();
        for (LocalDate date : dateList) {
            //查询日期对应营业额数据
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);

            HashMap map = new HashMap();
            map.put("begin", beginTime);
            map.put("end", endTime);
            map.put("status", Orders.COMPLETED);
            Double turnover = orderMapper.sumByMap(map);
            turnover = turnover == null ? 0.0 : turnover;
            turnoverList.add(turnover);

        }
        return TurnoverReportVO.builder()
                .dateList(StringUtils.join(dateList, ","))
                .turnoverList(StringUtils.join(turnoverList, ","))
                .build();
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
        //存放每天新增用户数量
        List<Integer> newUserList = new ArrayList<>();
        //存放每天总用户数量
        List<Integer> totalUserList = new ArrayList<>();

        for (LocalDate date : dateList) {
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);

            HashMap map = new HashMap();
            map.put("end", endTime);
            Integer totalUser = userMapper.countByMap(map);

            map.put("begin", beginTime);
            Integer newUser = userMapper.countByMap(map);

            totalUserList.add(totalUser);
            newUserList.add(newUser);

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

        //存放每天订单总数和有效订单数
        List<Integer> orderCountList = new ArrayList<>();
        List<Integer> validOrderCountList = new ArrayList<>();
        int totalOrderCount = 0;
        int validOrderCount = 0;

        //遍历dateList集合，查询每天的订单总数和有效订单数
        for (LocalDate date : dateList) {
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);

            HashMap<String, Object> map = new HashMap<>();
            map.put("begin", beginTime);
            map.put("end", endTime);

            //查询每天订单总数
            Integer orderCount = orderMapper.countByMap(map);
            orderCount = orderCount == null ? 0 : orderCount;
            orderCountList.add(orderCount);
            totalOrderCount += orderCount;

            //查询每天有效订单数
            map.put("status", Orders.COMPLETED);
            Integer validCount = orderMapper.countByMap(map);
            validCount = validCount == null ? 0 : validCount;
            validOrderCountList.add(validCount);
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































