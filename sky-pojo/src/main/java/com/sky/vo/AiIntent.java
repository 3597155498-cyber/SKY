package com.sky.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 结构化点餐意图（模型只产出这个，绝不产出菜品 id）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiIntent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 0 不辣 1 微辣 2 中辣 3 特辣；null 表示未提及 */
    private Integer spicy;

    /** 口味：麻辣/清淡/酸甜/咸香... */
    private List<String> flavors;

    /** 忌口食材：香菜/葱/蒜... */
    private List<String> excludeIngredients;

    /** 人均预算 */
    private Integer budgetPerPerson;

    /** 用餐人数 */
    private Integer people;

    /** 品类：荤菜/素菜/汤/主食/套餐 */
    private String category;

    /** 未归类的关键词，用于模糊检索兜底 */
    private String keyword;

    @JsonIgnore
    public boolean isEmpty() {
        boolean noSpicy = spicy == null;
        boolean noFlavor = flavors == null || flavors.isEmpty();
        boolean noExclude = excludeIngredients == null || excludeIngredients.isEmpty();
        boolean noBudget = budgetPerPerson == null;
        boolean noPeople = people == null;
        boolean noCategory = category == null || category.isEmpty();
        boolean noKeyword = keyword == null || keyword.isEmpty();
        return noSpicy && noFlavor && noExclude && noBudget && noPeople && noCategory && noKeyword;
    }
}
