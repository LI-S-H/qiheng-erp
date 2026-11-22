# -*- coding: utf-8 -*-
"""
ERP 演示数据生成器（v2）。

从已验证的主数据映射（categories.tsv、products.tsv）出发，重新生成供应商、客户、
商品、供货关系和近 12 个月完整业务链路数据。生成结果可直接导入空库，并由
verify-data-v2.sql 校验。

设计原则：
1. 金额口径统一按分：商品参考价、交易单价和金额都存 bigint 分，数量按 100 倍整数存储。
2. 库存、单据状态、评分全部由事实推导，不做随机填数。
3. 所有未完成单据满足后端提交/审核/确认的前置条件。
4. 输出确定（固定随机种子），可重复生成。

作者：Li
日期：2026-11-22
"""
from __future__ import annotations

import math
import random
import sys
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from typing import Iterable

BASE_DIR = Path(__file__).resolve().parent
OUT_DIR = BASE_DIR / "out"

# 业务时间窗口：今天为 2026-11-22，数据覆盖近 12 个月。
TODAY = date(2026, 11, 22)
WINDOW_START = date(2025, 11, 1)
WINDOW_END = TODAY - timedelta(days=1)

SEED = 20261122
rng = random.Random(SEED)

# 数量与金额存储倍率。
SCALE = 100

# 主数据 ID 段（沿用现有演示数据 ID 区间，避免与测试脚本硬编码的 ID 冲突）。
ID_WAREHOUSE = 8400000000004000001
ID_SUPPLIER = 8400000000002000001
ID_CUSTOMER = 8400000000003000001
ID_SUPPLIER_PRODUCT = 8400000000006000001
ID_PURCHASE_ORDER = 8400000000007000001
ID_PURCHASE_ITEM = 8400000000008000001
ID_INBOUND_BILL = 8400000000009000001
ID_INBOUND_ITEM = 8400000000010000001
ID_PURCHASE_STOCK_BILL = 8400000000011000001
ID_PURCHASE_STOCK_ITEM = 8400000000012000001
ID_SALES_ORDER = 8400000000013000001
ID_SALES_ITEM = 8400000000014000001
ID_OUTBOUND_BILL = 8400000000015000001
ID_OUTBOUND_ITEM = 8400000000016000001
ID_SALES_STOCK_BILL = 8400000000017000001
ID_SALES_STOCK_ITEM = 8400000000018000001
ID_WAREHOUSE_STOCK = 8400000000019000001
ID_RETURN_ORDER = 8400000000020000001
ID_RETURN_ITEM = 8400000000021000001
# 期初库存调整单使用独立区间，避免污染采购/销售验证区间。
ID_ADJUST_IN_BILL = 8400000000031000001
ID_ADJUST_IN_ITEM = 8400000000032000001
ID_ADJUST_STOCK_BILL = 8400000000033000001
ID_ADJUST_STOCK_ITEM = 8400000000034000001
# 调拨单独立区间。
ID_TRANSFER_OUT_BILL = 8400000000035000001
ID_TRANSFER_OUT_ITEM = 8400000000036000001
ID_TRANSFER_IN_BILL = 8400000000037000001
ID_TRANSFER_IN_ITEM = 8400000000038000001
ID_TRANSFER_STOCK_BILL = 8400000000039000001
ID_TRANSFER_STOCK_ITEM = 8400000000040000001
# 退货执行产生的出入库单独立区间。
ID_RETURN_IN_BILL = 8400000000041000001
ID_RETURN_IN_ITEM = 8400000000042000001
ID_RETURN_OUT_BILL = 8400000000043000001
ID_RETURN_OUT_ITEM = 8400000000044000001
ID_RETURN_STOCK_BILL = 8400000000045000001
ID_RETURN_STOCK_ITEM = 8400000000046000001


class IdGen:
    """连续 ID 生成器。"""

    def __init__(self, start: int) -> None:
        self.current = start

    def next(self) -> int:
        value = self.current
        self.current += 1
        return value


class DailyNoGen:
    """按业务日期生成单号：前缀 + 8 位日期 + 5 位序号。"""

    def __init__(self, prefix: str) -> None:
        self.prefix = prefix
        self.counters: dict[str, int] = {}

    def next(self, day: date) -> str:
        key = day.strftime("%Y%m%d")
        seq = self.counters.get(key, 0) + 1
        self.counters[key] = seq
        if seq > 99999:
            raise ValueError(f"{self.prefix} {key} 单号空间不足")
        return f"{self.prefix}{key}{seq:05d}"


def sql_quote(value: object) -> str:
    """SQL 字面量转义；None 输出 NULL。"""
    if value is None:
        return "NULL"
    if isinstance(value, Raw):
        return value.text
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, Decimal):
        return format(value, "f")
    if isinstance(value, datetime):
        return "'" + value.strftime("%Y-%m-%d %H:%M:%S") + "'"
    if isinstance(value, date):
        return "'" + value.strftime("%Y-%m-%d") + "'"
    text = str(value).replace("\\", "\\\\").replace("'", "''")
    return "'" + text + "'"


def money_to_cents(amount: Decimal) -> int:
    """元转分，四舍五入到整数分。"""
    return int((amount * SCALE).quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def qty_to_stored(qty: int) -> int:
    """整数数量转 100 倍存储。"""
    return int(qty) * SCALE


def round_half_up(value: float) -> int:
    return int(math.floor(value + 0.5))


def weighted_pick(items: Iterable, weights: Iterable[float]):
    """按权重随机选择。"""
    pool = list(items)
    weight_list = list(weights)
    total = sum(weight_list)
    if total <= 0:
        return rng.choice(pool)
    point = rng.random() * total
    cursor = 0.0
    for item, weight in zip(pool, weight_list):
        cursor += weight
        if point <= cursor:
            return item
    return pool[-1]


def random_datetime(day: date, start_hour: int = 8, end_hour: int = 20) -> datetime:
    """生成当天的工作时间。"""
    return datetime(day.year, day.month, day.day, rng.randint(start_hour, end_hour - 1),
                    rng.randint(0, 59), rng.randint(0, 59))


def month_starts(start: date, end: date) -> list[date]:
    result = []
    cursor = date(start.year, start.month, 1)
    while cursor <= end:
        result.append(cursor)
        if cursor.month == 12:
            cursor = date(cursor.year + 1, 1, 1)
        else:
            cursor = date(cursor.year, cursor.month + 1, 1)
    return result


# ---------------------------------------------------------------------------
# 主数据词库
# ---------------------------------------------------------------------------

# 上级分类画像：单位、参考采购价区间（元，生成时转分）、月销量区间、供应商行业、品牌。
CATEGORY_PROFILE: dict[str, dict] = {
    "休闲食品": dict(unit="袋", price=(4, 45), monthly=(180, 760), industry="食品",
                 brands=["乐享", "鲜语", "谷丰源", "香满屋"],
                 specs=["80g/袋", "120g/袋", "200g/袋", "350g/袋", "500g/袋"]),
    "水产海鲜": dict(unit="袋", price=(8, 70), monthly=(150, 500), industry="水产",
                 brands=["海之味", "鲜汇", "蓝湾"],
                 specs=["300g/袋", "500g/袋", "1kg/袋"]),
    "水果及果制品": dict(unit="罐", price=(6, 50), monthly=(160, 620), industry="食品",
                  brands=["果知味", "鲜果坊", "绿源"],
                  specs=["425g/罐", "850g/罐", "1kg/罐"]),
    "粮油调味": dict(unit="瓶", price=(5, 60), monthly=(200, 700), industry="粮油",
                 brands=["金穗", "味源", "禾丰"],
                 specs=["250ml/瓶", "500ml/瓶", "1L/瓶", "5L/桶"]),
    "乳制品": dict(unit="盒", price=(5, 45), monthly=(260, 900), industry="乳品",
                brands=["牧原鲜", "晨露", "优牧"],
                specs=["250ml×12盒", "250ml×24盒", "1kg/盒"]),
    "冷冻食品": dict(unit="袋", price=(8, 55), monthly=(180, 650), industry="冷链食品",
                 brands=["冰泉", "鲜冻", "北雪"],
                 specs=["300g/袋", "500g/袋", "1kg/袋"]),
    "肉类熟食": dict(unit="袋", price=(10, 60), monthly=(180, 600), industry="食品",
                 brands=["肉联", "鲜厨", "味珍"],
                 specs=["200g/袋", "300g/袋", "500g/袋"]),
    "纸塑用品": dict(unit="提", price=(6, 40), monthly=(120, 500), industry="日用品",
                 brands=["洁然", "家和", "纸优"],
                 specs=["6卷/提", "10卷/提", "12卷/提", "100只/包"]),
    "饮料": dict(unit="瓶", price=(3, 25), monthly=(300, 1200), industry="食品",
              brands=["沁泉", "果沁", "茶里"],
              specs=["330ml/瓶", "500ml/瓶", "1L/瓶", "2L/瓶"]),
    "清洁用品": dict(unit="瓶", price=(5, 50), monthly=(150, 520), industry="日用品",
                 brands=["净美", "家净", "清逸"],
                 specs=["500ml/瓶", "1L/瓶", "2L/桶"]),
    "面包与早餐": dict(unit="袋", price=(4, 30), monthly=(260, 900), industry="食品",
                  brands=["麦香", "晨麦", "焙乐"],
                  specs=["300g/袋", "500g/袋", "1kg/袋"]),
    "果酱与抹酱": dict(unit="瓶", price=(8, 40), monthly=(120, 430), industry="食品",
                  brands=["果丰", "甜源", "味好"],
                  specs=["200g/瓶", "380g/瓶", "500g/瓶"]),
    "糖果": dict(unit="袋", price=(5, 35), monthly=(160, 560), industry="食品",
              brands=["糖趣", "甜心", "乐糖"],
              specs=["100g/袋", "200g/袋", "500g/袋"]),
    "个人护理": dict(unit="瓶", price=(8, 80), monthly=(150, 520), industry="日用品",
                 brands=["净护", "舒雅", "齿洁"],
                 specs=["200ml/瓶", "400ml/瓶", "500ml/瓶", "1L/瓶"]),
    "日用五金": dict(unit="件", price=(6, 120), monthly=(30, 160), industry="日用品",
                 brands=["匠品", "家匠", "固力"],
                 specs=["1件", "2件/套", "5件/套"]),
    "酒类": dict(unit="瓶", price=(15, 260), monthly=(60, 260), industry="酒类",
              brands=["醇香", "青樽", "麦醇"],
              specs=["330ml/瓶", "500ml/瓶", "750ml/瓶"]),
    "家居日用": dict(unit="件", price=(8, 100), monthly=(50, 240), industry="日用品",
                 brands=["居雅", "暖居", "简家"],
                 specs=["1件", "2件/套", "4件/套"]),
    "罐头食品": dict(unit="罐", price=(5, 40), monthly=(120, 480), industry="食品",
                 brands=["罐鲜", "味丰", "海丰"],
                 specs=["200g/罐", "400g/罐", "800g/罐"]),
    "蔬菜与豆制品": dict(unit="袋", price=(3, 25), monthly=(240, 800), industry="食品",
                   brands=["田鲜", "绿田", "豆香"],
                   specs=["300g/袋", "500g/袋", "1kg/袋"]),
    "医药用品": dict(unit="盒", price=(8, 60), monthly=(40, 180), industry="医药",
                 brands=["康宁", "安泰", "和信"],
                 specs=["12片/盒", "24片/盒", "100ml/瓶"]),
    "蛋类": dict(unit="盒", price=(8, 30), monthly=(200, 600), industry="食品",
              brands=["晨蛋", "鲜蛋坊"],
              specs=["10枚/盒", "20枚/盒", "30枚/盒"]),
    "杂志": dict(unit="本", price=(8, 45), monthly=(20, 90), industry="书刊",
              brands=["博阅", "新知", "视界"],
              specs=["1本", "1年12期"]),
}

# 下级分类的规格与单位，保证商品名称与包装形态匹配。
SUB_SPECS: dict[str, tuple[str, list[str]]] = {
    # 休闲食品
    "坚果": ("袋", ["150g/袋", "250g/袋", "500g/袋"]),
    "薯片": ("袋", ["70g/袋", "110g/袋", "180g/袋"]),
    "肉干": ("袋", ["80g/袋", "150g/袋", "250g/袋"]),
    "甜饼干": ("盒", ["200g/盒", "400g/盒", "800g/盒"]),
    "椒盐卷饼": ("袋", ["100g/袋", "200g/袋"]),
    "爆米花": ("袋", ["80g/袋", "150g/袋"]),
    "苏打饼干": ("盒", ["200g/盒", "400g/盒"]),
    "蘸酱": ("瓶", ["200g/瓶", "340g/瓶"]),
    "甜甜圈": ("盒", ["6个/盒", "12个/盒"]),
    "干果": ("袋", ["200g/袋", "500g/袋"]),
    # 水产海鲜
    "贝类": ("袋", ["400g/袋", "1kg/袋"]),
    "鲜鱼": ("袋", ["500g/袋", "1kg/袋"]),
    # 水果及果制品
    "水果罐头": ("罐", ["425g/罐", "850g/罐"]),
    "新鲜水果": ("袋", ["1kg/袋", "2kg/箱"]),
    # 粮油调味
    "香辛料": ("瓶", ["30g/瓶", "50g/瓶", "100g/瓶"]),
    "意大利面": ("袋", ["400g/袋", "500g/袋", "1kg/袋"]),
    "调味酱": ("瓶", ["200g/瓶", "300g/瓶", "500g/瓶"]),
    "食用油": ("桶", ["1.8L/桶", "5L/桶"]),
    "食糖": ("袋", ["400g/袋", "1kg/袋"]),
    "大米": ("袋", ["5kg/袋", "10kg/袋", "25kg/袋"]),
    # 乳制品
    "酸奶": ("杯", ["100g×8杯", "200g×4杯"]),
    "奶酪": ("盒", ["200g/盒", "500g/盒"]),
    "酸奶油": ("瓶", ["200g/瓶", "300g/瓶"]),
    "白软干酪": ("盒", ["250g/盒", "500g/盒"]),
    "牛奶": ("盒", ["250ml×12盒", "250ml×24盒", "1L/盒"]),
    # 冷冻食品
    "冰淇淋": ("桶", ["500ml/桶", "1L/桶"]),
    "冷冻套餐": ("盒", ["350g/盒", "500g/盒"]),
    "煎饼粉": ("袋", ["500g/袋", "1kg/袋"]),
    "冷冻煎饼": ("袋", ["400g/袋", "800g/袋"]),
    "华夫饼": ("袋", ["400g/袋", "800g/袋"]),
    "冷冻蔬菜": ("袋", ["500g/袋", "1kg/袋"]),
    "冷冻薯条": ("袋", ["1kg/袋", "2kg/袋"]),
    "冷冻披萨": ("盒", ["350g/盒", "450g/盒"]),
    "冷冻鸡肉": ("袋", ["500g/袋", "1kg/袋"]),
    "冰棒": ("盒", ["10支/盒", "20支/盒"]),
    # 肉类熟食
    "切片熟肉": ("袋", ["150g/袋", "250g/袋"]),
    "熟食沙拉": ("盒", ["250g/盒", "400g/盒"]),
    "汉堡肉": ("袋", ["500g/袋", "1kg/袋"]),
    "熟鸡肉": ("袋", ["300g/袋", "500g/袋"]),
    "热狗": ("袋", ["400g/袋", "800g/袋"]),
    "午餐肉": ("罐", ["198g/罐", "340g/罐"]),
    # 纸塑用品
    "纸巾": ("提", ["6卷/提", "10卷/提", "12卷/提"]),
    "纸质餐具": ("包", ["50只/包", "100只/包"]),
    "塑料餐具": ("包", ["50只/包", "100只/包"]),
    # 饮料
    "碳酸饮料": ("罐", ["330ml×24罐", "500ml×12瓶"]),
    "果汁": ("瓶", ["500ml/瓶", "1L/瓶", "2L/瓶"]),
    "热巧克力饮品": ("罐", ["300g/罐", "500g/罐"]),
    "风味饮料": ("瓶", ["500ml/瓶", "1L/瓶"]),
    "咖啡": ("袋", ["200g/袋", "500g/袋"]),
    # 清洁用品
    "空气清新剂": ("瓶", ["300ml/瓶", "500ml/瓶"]),
    "家居清洁剂": ("瓶", ["500ml/瓶", "1L/瓶"]),
    "马桶刷": ("个", ["1个", "2个/套"]),
    "锅具刷": ("个", ["1个", "2个/套"]),
    "金属清洁剂": ("瓶", ["300ml/瓶", "500ml/瓶"]),
    "洗碗机清洁剂": ("盒", ["500g/盒", "1kg/盒"]),
    "洗衣液": ("瓶", ["1L/瓶", "2L/瓶", "3L/瓶"]),
    # 面包与早餐
    "百吉饼": ("袋", ["6个/袋", "12个/袋"]),
    "英式松饼": ("袋", ["6个/袋", "12个/袋"]),
    "切片面包": ("袋", ["400g/袋", "700g/袋"]),
    "早餐谷物": ("盒", ["375g/盒", "750g/盒"]),
    # 果酱与抹酱
    "果冻": ("盒", ["100g/盒", "200g/盒"]),
    "果酱": ("瓶", ["200g/瓶", "340g/瓶"]),
    "水果蜜饯": ("袋", ["150g/袋", "300g/袋"]),
    "花生酱": ("瓶", ["340g/瓶", "500g/瓶"]),
    # 糖果
    "巧克力糖": ("盒", ["100g/盒", "200g/盒"]),
    "口香糖": ("盒", ["5片/盒", "10片/盒"]),
    "硬糖": ("袋", ["150g/袋", "300g/袋"]),
    # 个人护理
    "牙膏": ("支", ["90g/支", "120g/支", "180g/支"]),
    "漱口水": ("瓶", ["250ml/瓶", "500ml/瓶"]),
    "护发素": ("瓶", ["200ml/瓶", "400ml/瓶"]),
    "洗发水": ("瓶", ["400ml/瓶", "750ml/瓶"]),
    "牙刷": ("支", ["1支", "4支/套"]),
    # 日用五金
    "锅具": ("件", ["1件", "2件/套", "4件/套"]),
    "日用工具": ("件", ["1件", "5件/套"]),
    "海绵": ("包", ["3个/包", "6个/包"]),
    "灯泡": ("盒", ["1只/盒", "2只/盒"]),
    "电池": ("板", ["4节/板", "8节/板"]),
    "螺丝刀": ("把", ["1把", "2把/套", "6把/套"]),
    # 酒类
    "啤酒": ("瓶", ["500ml/瓶", "500ml×12罐"]),
    "葡萄酒": ("瓶", ["375ml/瓶", "750ml/瓶"]),
    # 家居日用
    "蜡烛": ("罐", ["150g/罐", "300g/罐"]),
    "太阳镜": ("副", ["1副", "2副/套"]),
    "地图": ("张", ["1张", "1套"]),
    # 罐头食品
    "汤罐头": ("罐", ["300g/罐", "400g/罐"]),
    "蔬菜罐头": ("罐", ["400g/罐", "800g/罐"]),
    "金枪鱼罐头": ("罐", ["185g/罐", "400g/罐"]),
    "虾罐头": ("罐", ["185g/罐", "400g/罐"]),
    "凤尾鱼罐头": ("罐", ["100g/罐", "200g/罐"]),
    "蛤蜊罐头": ("罐", ["185g/罐", "400g/罐"]),
    "牡蛎罐头": ("罐", ["185g/罐", "400g/罐"]),
    "沙丁鱼罐头": ("罐", ["185g/罐", "400g/罐"]),
    # 蔬菜与豆制品
    "新鲜蔬菜": ("袋", ["500g/袋", "1kg/袋"]),
    "豆腐": ("盒", ["300g/盒", "500g/盒"]),
    # 医药用品
    "阿司匹林": ("盒", ["12片/盒", "24片/盒"]),
    "布洛芬": ("盒", ["12片/盒", "20片/盒"]),
    "对乙酰氨基酚": ("盒", ["10片/盒", "20片/盒"]),
    "鼻喷剂": ("瓶", ["10ml/瓶", "20ml/瓶"]),
    "感冒药": ("盒", ["6袋/盒", "12袋/盒"]),
    # 蛋类
    "鸡蛋": ("盒", ["10枚/盒", "20枚/盒", "30枚/盒"]),
    # 杂志
    "体育杂志": ("本", ["1本", "1年12期"]),
    "家居杂志": ("本", ["1本", "1年12期"]),
    "时尚杂志": ("本", ["1本", "1年12期"]),
    "电脑杂志": ("本", ["1本", "1年12期"]),
    "汽车杂志": ("本", ["1本", "1年12期"]),
}

SUPPLIER_INDUSTRY_SUFFIX = {
    "食品": ["食品供应链", "食品贸易", "商贸"],
    "水产": ["水产供应链", "水产贸易"],
    "粮油": ["粮油贸易", "粮油供应链"],
    "乳品": ["乳品供应链", "乳业"],
    "冷链食品": ["冷链食品", "冷链物流"],
    "日用品": ["日用品商贸", "日用品供应链"],
    "酒类": ["酒业贸易", "酒类供应链"],
    "医药": ["医药供应链", "医药商贸"],
    "书刊": ["书刊发行", "文化传媒"],
}

CITY_WORDS = ["上海", "杭州", "苏州", "南京", "宁波", "广州", "深圳", "佛山", "厦门",
              "福州", "北京", "天津", "青岛", "济南", "成都", "重庆", "武汉", "长沙",
              "郑州", "合肥", "西安", "昆明"]
DISTRICTS = {
    "上海": ["浦东新区", "闵行区", "嘉定区", "松江区"],
    "杭州": ["余杭区", "萧山区", "钱塘区"],
    "苏州": ["工业园区", "吴中区", "相城区"],
    "南京": ["江宁区", "雨花台区", "栖霞区"],
    "宁波": ["鄞州区", "北仑区", "海曙区"],
    "广州": ["白云区", "番禺区", "黄埔区"],
    "深圳": ["宝安区", "龙岗区", "光明区"],
    "佛山": ["南海区", "顺德区", "禅城区"],
    "厦门": ["集美区", "海沧区", "同安区"],
    "福州": ["仓山区", "闽侯县", "马尾区"],
    "北京": ["顺义区", "大兴区", "通州区"],
    "天津": ["西青区", "北辰区", "滨海新区"],
    "青岛": ["城阳区", "黄岛区", "即墨区"],
    "济南": ["历城区", "槐荫区", "章丘区"],
    "成都": ["双流区", "龙泉驿区", "新都区"],
    "重庆": ["渝北区", "九龙坡区", "巴南区"],
    "武汉": ["江夏区", "东西湖区", "蔡甸区"],
    "长沙": ["望城区", "雨花区", "长沙县"],
    "郑州": ["中牟县", "航空港区", "金水区"],
    "合肥": ["肥东县", "蜀山区", "包河区"],
    "西安": ["长安区", "未央区", "灞桥区"],
    "昆明": ["呈贡区", "官渡区", "安宁市"],
}
ROAD_WORDS = ["物流大道", "兴业路", "金桥路", "临港大道", "产业园路", "振兴路",
              "创业大道", "商贸路", "东风路", "云谷路"]
COMPANY_SUFFIX = ["有限公司", "有限责任公司"]
TRADE_WORDS = ["鲜丰", "禾润", "汇达", "恒泰", "嘉禾", "万隆", "瑞丰", "康源", "金穗",
               "百川", "永盛", "合顺", "利民", "天成", "恒信", "华泽", "润丰", "德昌",
               "兴茂", "广源", "泰和", "联创", "盛达", "安达", "宏远", "正丰", "源泰",
               "东盛", "金桥", "长丰"]
SURNAMES = ["王", "李", "张", "刘", "陈", "杨", "黄", "赵", "周", "吴", "徐", "孙",
            "马", "朱", "胡", "郭", "何", "高", "林", "罗"]
GIVEN_NAMES = ["建国", "志强", "晓东", "海涛", "文斌", "俊杰", "丽娟", "秀英", "敏华",
               "桂英", "伟", "静", "磊", "娜", "强", "洋", "勇", "艳", "军", "杰"]
CUSTOMER_TRADE_WORDS = ["惠邻", "好又鲜", "家和", "鲜达", "乐购", "万佳", "百汇",
                        "优鲜", "邻里", "聚福", "盛客", "福来", "新禾", "康惠", "悦享"]
CUSTOMER_TYPES = ["连锁超市", "便利店", "餐饮管理", "电子商务", "社区团购", "酒店管理"]

WAREHOUSE_DEFS = [
    ("WH000001", "上海浦东中心仓", "上海", "浦东新区", "临港物流园 12 号", "周海峰"),
    ("WH000002", "广州白云分拨仓", "广州", "白云区", "太和镇物流大道 88 号", "陈志明"),
    ("WH000003", "北京顺义北仓", "北京", "顺义区", "空港物流园 B 区 6 号", "李文静"),
    ("WH000004", "成都双流西仓", "成都", "双流区", "西航港物流大道 66 号", "杨晓东"),
    ("WH000005", "武汉江夏南仓", "武汉", "江夏区", "金口大道 18 号", "刘海涛"),
]

PAYMENT_TERMS = ["月结30天", "月结45天", "月结60天", "货到付款", "预付30%尾款月结"]
CREDIT_LEVELS = [50000, 100000, 200000, 500000, 1000000]
SERVICE_REASONS = [
    "响应及时，售后配合度高",
    "配合新品导入，服务稳定",
    "订单沟通顺畅，问题处理及时",
    "常规合作，服务表现一般",
    "旺季响应偏慢，售后跟进不足",
    "退换货处理较慢，需持续跟进",
]


@dataclass
class Category:
    id: int
    parent_id: int
    name: str
    status: int


@dataclass
class ProductSeed:
    id: int
    code: str
    category_id: int


def load_categories() -> list[Category]:
    rows: list[Category] = []
    with (BASE_DIR / "categories.tsv").open("r", encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 4:
                continue
            rows.append(Category(int(parts[0]), int(parts[1]), parts[2], int(parts[3])))
    if len(rows) != 126:
        raise ValueError(f"分类应为 126 个，实际 {len(rows)}")
    return rows


def load_products() -> list[ProductSeed]:
    rows: list[ProductSeed] = []
    with (BASE_DIR / "products.tsv").open("r", encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            rows.append(ProductSeed(int(parts[0]), parts[1], int(parts[2])))
    if len(rows) != 1560:
        raise ValueError(f"商品应为 1560 个，实际 {len(rows)}")
    return rows


@dataclass
class Warehouse:
    id: int
    code: str
    name: str
    city: str
    district: str
    address: str
    contact: str
    phone: str


@dataclass
class Supplier:
    id: int
    code: str
    name: str
    contact: str
    phone: str
    address: str
    payment_terms: str
    service_score: int | None
    service_reason: str
    specialties: list[str]
    quality_factor: float          # 质量水平：0.985 表示约 1.5% 次品
    lead_days: int                 # 平均交期
    lead_jitter: int               # 交期波动天数
    on_time_rate: float            # 按时到货概率
    scale: float                   # 供应规模，影响可承接采购量
    has_recent_purchase: bool      # 近 180 天是否有采购样本
    # 以下字段由交易事实计算后回填。
    delivery_score: int | None = None
    quality_score: int | None = None
    price_score: int | None = None
    overall_score: int | None = None
    score_status: str = "NOT_READY"
    score_basis_amount: int = 0
    avg_delivery_days: float | None = None


@dataclass
class Customer:
    id: int
    code: str
    name: str
    contact: str
    phone: str
    address: str
    credit_limit: int
    city: str
    warehouse_index: int


@dataclass
class Product:
    id: int
    code: str
    name: str
    category_id: int
    parent_name: str
    sub_name: str
    brand: str
    unit: str
    specification: str
    price_cents: int               # 参考采购价（分）
    sale_price_cents: int          # 参考销售价（分）
    safety_stock: int              # 件
    monthly_demand: int            # 月均销量（件）
    seasonal: dict[int, float]     # 月份季节系数
    warehouses: list[int]          # 库存仓库索引


@dataclass
class SupplyRelation:
    id: int
    supplier_id: int
    product_id: int
    supplier: Supplier
    product: Product
    quote_cents: int
    quote_valid_until: date
    min_order_qty: int
    status: int
    kind: str                      # PRIMARY / LOW_COST / PREMIUM
    has_history: bool
    last_purchase_at: datetime | None = None
    latest_purchase_price: int | None = None
    score_basis_amount: int = 0
    quality_amount_good: int = 0
    quality_amount_bad: int = 0
    price_score: int | None = None
    quality_score: int | None = None
    recommend_score: int | None = None
    avg_delivery_days: float | None = None
    deliveries: list[tuple[datetime, datetime, int]] = field(default_factory=list)  # 审核时间, 完全入库时间, 金额


@dataclass
class PurchaseLine:
    item_id: int
    relation: SupplyRelation
    quantity: int
    unit_price: int
    total_amount: int
    inbound_qty: int = 0
    selected_score: int | None = None
    # (本次入库量, 确认日, 合格数量, 次品数量)
    batches: list[tuple[int, date, int, int]] = field(default_factory=list)


@dataclass
class PurchaseOrder:
    id: int
    no: str
    supplier: Supplier
    warehouse_index: int
    status: str
    created_at: datetime
    expected_arrival: date
    submitted_at: datetime | None
    approved_at: datetime | None
    fully_received_at: datetime | None
    cancelled_at: datetime | None
    cancel_reason: str
    cancel_affects_score: int
    lines: list[PurchaseLine]
    inbound_bills: list["InboundBill"] = field(default_factory=list)


@dataclass
class SalesLine:
    item_id: int
    product: Product
    quantity: int
    unit_price: int
    total_amount: int
    locked_qty: int = 0
    outbound_qty: int = 0


@dataclass
class SalesOrder:
    id: int
    no: str
    customer: Customer
    warehouse_index: int
    status: str
    created_at: datetime
    expected_delivery: date
    submitted_at: datetime | None
    approved_at: datetime | None
    locked_at: datetime | None
    lines: list[SalesLine]
    outbound_bills: list["OutboundBill"] = field(default_factory=list)


@dataclass
class InboundBill:
    id: int
    no: str
    order: PurchaseOrder
    status: str
    created_at: datetime
    confirmed_at: datetime | None
    lines: list[dict]
    bill_type: str = "PURCHASE_IN"
    entry_mode: str = "SOURCE_GENERATED"
    source_type: str = "PURCHASE_ORDER"
    stock_bill_id: int | None = None
    return_order: "ReturnOrder | None" = None


@dataclass
class OutboundBill:
    id: int
    no: str
    order: SalesOrder
    status: str
    created_at: datetime
    confirmed_at: datetime | None
    lines: list[dict]
    bill_type: str = "SALES_OUT"
    entry_mode: str = "SOURCE_GENERATED"
    source_type: str = "SALES_ORDER"
    stock_bill_id: int | None = None
    return_order: "ReturnOrder | None" = None


@dataclass
class ReturnOrder:
    id: int
    no: str
    return_type: str
    source_order_id: int
    source_order_no: str
    party_id: int
    party_code: str
    party_name: str
    warehouse_index: int
    expected_execution_date: date
    status: str
    created_at: datetime
    submitted_at: datetime | None
    approved_at: datetime | None
    lines: list[dict]
    total_amount: int
    reason_code: str
    return_reason: str
    handling_type: str
    execution_bill: "InboundBill | OutboundBill | None" = None


def build_warehouses() -> list[Warehouse]:
    result = []
    for index, (code, name, city, district, road, contact) in enumerate(WAREHOUSE_DEFS):
        result.append(Warehouse(
            id=ID_WAREHOUSE + index,
            code=code,
            name=name,
            city=city,
            district=district,
            address=f"{city}{district}{road}",
            contact=contact,
            phone=f"1{rng.choice('3456789')}{rng.randint(100000000, 999999999)}",
        ))
    return result


def build_suppliers(categories: list[Category]) -> list[Supplier]:
    parent_categories = [c for c in categories if c.parent_id == 0]
    # 供应商按上级分类分配：食品类 150 家，日用品 40 家，其余 10 家。
    food_names = ["休闲食品", "水产海鲜", "水果及果制品", "粮油调味", "乳制品", "冷冻食品",
                  "肉类熟食", "面包与早餐", "果酱与抹酱", "糖果", "酒类", "罐头食品",
                  "蔬菜与豆制品", "蛋类"]
    daily_names = ["纸塑用品", "清洁用品", "个人护理", "日用五金", "家居日用"]
    other_names = ["饮料", "医药用品", "杂志"]
    allocation: list[str] = []
    allocation += [name for name in food_names for _ in range(11)]      # 154
    allocation += [name for name in daily_names for _ in range(8)]      # 40
    allocation += ["饮料"] * 3 + ["医药用品"] * 2 + ["杂志"] * 1          # 6
    allocation = allocation[:200]
    while len(allocation) < 200:
        allocation.append("饮料")

    used_names: set[str] = set()
    result: list[Supplier] = []
    # 供应商整体分三档：优质 20%、普通 60%、尾部 20%；另有约 15% 无近 180 天样本。
    for index in range(200):
        parent_name = allocation[index]
        profile = CATEGORY_PROFILE[parent_name]
        industry = profile["industry"]
        suffix = rng.choice(SUPPLIER_INDUSTRY_SUFFIX[industry])
        for _ in range(200):
            city = rng.choice(CITY_WORDS)
            trade = rng.choice(TRADE_WORDS)
            name = f"{city}{trade}{suffix}{rng.choice(COMPANY_SUFFIX)}"
            if name not in used_names:
                used_names.add(name)
                break
        else:
            raise RuntimeError("供应商名称空间不足")
        tier = "premium" if index % 5 == 0 else ("tail" if index % 5 == 4 else "normal")
        if tier == "premium":
            quality_factor = rng.uniform(0.995, 0.999)
            lead_days = rng.randint(3, 6)
            lead_jitter = rng.randint(1, 3)
            on_time = rng.uniform(0.93, 0.99)
            scale = rng.uniform(1.1, 1.4)
        elif tier == "tail":
            quality_factor = rng.uniform(0.935, 0.975)
            lead_days = rng.randint(10, 18)
            lead_jitter = rng.randint(3, 7)
            on_time = rng.uniform(0.68, 0.84)
            scale = rng.uniform(0.6, 0.9)
        else:
            quality_factor = rng.uniform(0.975, 0.995)
            lead_days = rng.randint(5, 11)
            lead_jitter = rng.randint(2, 5)
            on_time = rng.uniform(0.82, 0.94)
            scale = rng.uniform(0.85, 1.15)
        has_service = rng.random() > 0.12
        service_score = None
        service_reason = ""
        if has_service:
            base = {"premium": (9000, 9750), "normal": (8000, 9300), "tail": (7200, 8600)}[tier]
            service_score = rng.randint(base[0], base[1])
            service_reason = rng.choice(SERVICE_REASONS)
        has_recent = rng.random() > 0.15
        specialties = [parent_name]
        if rng.random() < 0.25:
            specialties.append(rng.choice([p for p in CATEGORY_PROFILE if p != parent_name]))
        city = rng.choice(CITY_WORDS)
        result.append(Supplier(
            id=ID_SUPPLIER + index,
            code=f"S{index + 1:04d}",
            name=name,
            contact=rng.choice(SURNAMES) + rng.choice(GIVEN_NAMES),
            phone=f"1{rng.choice('3456789')}{rng.randint(100000000, 999999999)}",
            address=f"{city}{rng.choice(DISTRICTS[city])}{rng.choice(ROAD_WORDS)}{rng.randint(1, 999)}号",
            payment_terms=rng.choice(PAYMENT_TERMS),
            service_score=service_score,
            service_reason=service_reason,
            specialties=specialties,
            quality_factor=quality_factor,
            lead_days=lead_days,
            lead_jitter=lead_jitter,
            on_time_rate=on_time,
            scale=scale,
            has_recent_purchase=has_recent,
        ))
    return result


def build_customers(warehouses: list[Warehouse]) -> list[Customer]:
    used: set[str] = set()
    result = []
    city_to_warehouse = {w.city: index for index, w in enumerate(warehouses)}
    fallback_cities = list(city_to_warehouse.keys())
    for index in range(200):
        city = rng.choice(list(DISTRICTS.keys()))
        trade = rng.choice(CUSTOMER_TRADE_WORDS)
        customer_type = rng.choice(CUSTOMER_TYPES)
        name = f"{city}{trade}{customer_type}有限公司"
        while name in used:
            trade = rng.choice(CUSTOMER_TRADE_WORDS)
            customer_type = rng.choice(CUSTOMER_TYPES)
            name = f"{city}{trade}{customer_type}有限公司"
        used.add(name)
        credit = rng.choice(CREDIT_LEVELS)
        warehouse_index = city_to_warehouse.get(city, rng.randrange(len(warehouses)))
        result.append(Customer(
            id=ID_CUSTOMER + index,
            code=f"C{index + 1:04d}",
            name=name,
            contact=rng.choice(SURNAMES) + rng.choice(GIVEN_NAMES),
            phone=f"1{rng.choice('3456789')}{rng.randint(100000000, 999999999)}",
            address=f"{city}{rng.choice(DISTRICTS[city])}{rng.choice(ROAD_WORDS)}{rng.randint(1, 999)}号",
            credit_limit=credit * SCALE,
            city=city,
            warehouse_index=warehouse_index,
        ))
    return result


def build_products(seeds: list[ProductSeed], categories: list[Category]) -> list[Product]:
    category_by_id = {c.id: c for c in categories}
    parent_by_id = {c.id: c for c in categories if c.parent_id == 0}
    sub_products: dict[int, list[ProductSeed]] = {}
    for seed in seeds:
        sub_products.setdefault(seed.category_id, []).append(seed)

    result: list[Product] = []
    for category in categories:
        if category.parent_id == 0:
            continue
        profile = CATEGORY_PROFILE.get(parent_by_id[category.parent_id].name)
        if profile is None:
            raise ValueError(f"缺少分类画像：{category.name}")
        items = sub_products.get(category.id, [])
        if not items:
            continue
        price_low, price_high = profile["price"]
        monthly_low, monthly_high = profile["monthly"]
        unit, specs = SUB_SPECS.get(category.name, (profile["unit"], profile["specs"]))
        # 品牌 × 修饰 × 规格的笛卡尔积打乱后取用，保证同分类商品名不重复。
        flavors = ["", "精选", "家庭装", "经典", "优选", "经济装", "量贩装",
                   "便携装", "礼盒装", "升级款"]
        spec_variants = []
        for spec in specs:
            spec_variants.append(spec)
            spec_variants.append(spec + "×2装")
            spec_variants.append(spec + "×3装")
        combos = [(brand, flavor, spec) for brand in profile["brands"]
                  for flavor in flavors for spec in spec_variants]
        if len(combos) < len(items):
            raise ValueError(f"分类 {category.name} 商品名组合不足：{len(combos)} < {len(items)}")
        rng.shuffle(combos)
        for order, seed in enumerate(items):
            brand, flavor, spec = combos[order]
            name = f"{brand} {category.name}{flavor} {spec}".replace("  ", " ")
            # 参考价按区间分散，保留两位小数，避免大量重复价格。
            ratio = (order + 1) / (len(items) + 1)
            base = price_low + (price_high - price_low) * ratio
            price_cents = max(50, round((base + rng.uniform(-0.6, 0.6)) * SCALE))
            margin = rng.uniform(1.08, 1.65)
            sale_cents = max(price_cents + 10, round(price_cents * margin))
            monthly = rng.randint(monthly_low, monthly_high)
            # 快消品在夏季/春节有波动，耐用品基本平稳。
            seasonal = {
                1: 1.05, 2: 0.72, 3: 1.02, 4: 1.0, 5: 1.02, 6: 1.08,
                7: 1.12, 8: 1.15, 9: 1.05, 10: 1.0, 11: 1.06, 12: 1.18,
            }
            if parent_by_id[category.parent_id].name in ("饮料", "冷冻食品", "冰淇淋"):
                seasonal = {1: 0.72, 2: 0.65, 3: 0.85, 4: 0.95, 5: 1.1, 6: 1.3,
                            7: 1.42, 8: 1.45, 9: 1.2, 10: 0.95, 11: 0.8, 12: 0.75}
            elif parent_by_id[category.parent_id].name in ("糖果", "酒类", "休闲食品"):
                seasonal = {1: 1.35, 2: 1.5, 3: 0.95, 4: 0.9, 5: 0.92, 6: 0.95,
                            7: 0.95, 8: 0.95, 9: 0.98, 10: 1.0, 11: 1.02, 12: 1.3}
            safety = {"休闲食品": (10, 60), "水产海鲜": (5, 30), "水果及果制品": (10, 50),
                      "粮油调味": (10, 60), "乳制品": (15, 80), "冷冻食品": (10, 60),
                      "肉类熟食": (8, 40), "纸塑用品": (10, 60), "饮料": (20, 100),
                      "清洁用品": (8, 40), "面包与早餐": (15, 70), "果酱与抹酱": (6, 30),
                      "糖果": (10, 50), "个人护理": (8, 40), "日用五金": (3, 15),
                      "酒类": (5, 25), "家居日用": (3, 20), "罐头食品": (8, 40),
                      "蔬菜与豆制品": (10, 50), "医药用品": (3, 15), "蛋类": (10, 40),
                      "杂志": (1, 6)}[parent_by_id[category.parent_id].name]
            safety_stock = rng.randint(safety[0], safety[1])
            # 仓库分配：70% 单仓，22% 双仓，8% 三仓；主仓按区域权重分配，刻意保留仓库负载差异。
            warehouse_count = 1 if rng.random() < 0.70 else (2 if rng.random() < 0.75 else 3)
            primary = weighted_pick(range(len(WAREHOUSE_DEFS)), [0.32, 0.24, 0.18, 0.15, 0.11])
            others = [i for i in range(len(WAREHOUSE_DEFS)) if i != primary]
            rng.shuffle(others)
            warehouses = [primary] + others[:warehouse_count - 1]
            result.append(Product(
                id=seed.id,
                code=seed.code,
                name=name,
                category_id=category.id,
                parent_name=parent_by_id[category.parent_id].name,
                sub_name=category.name,
                brand=brand,
                unit=unit,
                specification=spec,
                price_cents=price_cents,
                sale_price_cents=sale_cents,
                safety_stock=safety_stock,
                monthly_demand=monthly,
                seasonal=seasonal,
                warehouses=warehouses,
            ))
    if len(result) != 1560:
        raise ValueError(f"商品生成数量错误：{len(result)}")
    return result


def build_supply_relations(products: list[Product], suppliers: list[Supplier]) -> dict[int, list[SupplyRelation]]:
    suppliers_by_specialty: dict[str, list[Supplier]] = {}
    for supplier in suppliers:
        for specialty in supplier.specialties:
            suppliers_by_specialty.setdefault(specialty, []).append(supplier)

    relations: dict[int, list[SupplyRelation]] = {}
    next_id = ID_SUPPLIER_PRODUCT
    for product in products:
        pool = suppliers_by_specialty.get(product.parent_name, suppliers)
        # 主源从专长供应商中选，优先有近期采购样本的。
        primary_pool = [s for s in pool if s.has_recent_purchase] or pool
        primary = rng.choice(primary_pool)
        count = 1
        roll = rng.random()
        if roll < 0.20:
            count = 2
        elif roll < 0.28:
            count = 3
        chosen = [primary]
        remaining = [s for s in pool if s.id != primary.id]
        rng.shuffle(remaining)
        chosen += remaining[: count - 1]
        product_relations = []
        for index, supplier in enumerate(chosen):
            if index == 0:
                kind = "PRIMARY"
                quote_ratio = rng.uniform(0.96, 1.06)
                has_history = True
            else:
                kind = "LOW_COST" if rng.random() < 0.55 else "PREMIUM"
                if kind == "LOW_COST":
                    quote_ratio = rng.uniform(0.86, 0.94)
                else:
                    quote_ratio = rng.uniform(1.04, 1.14)
                has_history = rng.random() < 0.55
                if not has_history and rng.random() < 0.35:
                    # 部分新供货关系直接处于停用状态，用于演示候选过滤。
                    status = 0
                else:
                    status = 1
            quote = max(10, round(product.price_cents * quote_ratio))
            if rng.random() < 0.18:
                valid_until = TODAY - timedelta(days=rng.randint(1, 90))
            else:
                valid_until = TODAY + timedelta(days=rng.randint(20, 180))
            unit_moq = {"提": 5, "本": 5, "件": 2, "盒": 10, "袋": 20, "瓶": 20, "罐": 20}
            moq = rng.choice([1, 2, 5, 10, 20, 30, 50, 100]) * (2 if product.unit in ("件", "提") else 1)
            relation = SupplyRelation(
                id=next_id,
                supplier_id=supplier.id,
                product_id=product.id,
                supplier=supplier,
                product=product,
                quote_cents=quote,
                quote_valid_until=valid_until,
                min_order_qty=moq,
                status=status if index > 0 else 1,
                kind=kind,
                has_history=has_history,
            )
            next_id += 1
            product_relations.append(relation)
        relations[product.id] = product_relations
    return relations


def dump_master_data(warehouses: list[Warehouse], suppliers: list[Supplier],
                     customers: list[Customer], products: list[Product],
                     categories: list[Category], relations: dict[int, list[SupplyRelation]]) -> list[str]:
    """输出主数据 INSERT 语句。"""
    chunks: list[str] = []

    def emit(table: str, columns: list[str], rows: list[list[object]], chunk_size: int = 400) -> None:
        if not rows:
            return
        header = f"INSERT INTO `{table}` ({', '.join('`' + c + '`' for c in columns)}) VALUES\n"
        for start in range(0, len(rows), chunk_size):
            part = rows[start:start + chunk_size]
            values = ",\n".join("(" + ", ".join(sql_quote(v) for v in row) + ")" for row in part)
            chunks.append(header + values + ";\n")

    emit("product_category",
         ["id", "parent_id", "category_name", "status", "create_time", "update_time", "version"],
         [[c.id, c.parent_id, c.name, c.status, "2025-10-15 09:00:00", "2025-10-15 09:00:00", 0]
          for c in categories])

    emit("warehouse",
         ["id", "warehouse_code", "warehouse_name", "contact_name", "contact_phone", "address",
          "status", "create_time", "update_time", "remark", "version"],
         [[w.id, w.code, w.name, w.contact, w.phone, w.address, 1,
           "2025-10-15 09:00:00", "2025-10-15 09:00:00", "", 0] for w in warehouses])

    emit("supplier",
         ["id", "supplier_code", "supplier_name", "contact_name", "contact_phone", "address",
          "payment_terms", "overall_score", "delivery_score", "quality_score", "price_score",
          "service_score", "service_score_reason", "avg_delivery_days", "score_basis_amount",
          "score_status", "status", "create_time", "update_time", "updated_by_id", "updated_by_name",
          "remark", "version"],
         [[s.id, s.code, s.name, s.contact, s.phone, s.address, s.payment_terms,
           s.overall_score, s.delivery_score, s.quality_score, s.price_score,
           s.service_score, s.service_reason, s.avg_delivery_days, s.score_basis_amount,
           s.score_status, 1, "2025-10-15 09:00:00", "2026-11-21 18:00:00",
           None, "", "由演示数据生成器创建", 0] for s in suppliers])

    emit("customer",
         ["id", "customer_code", "customer_name", "contact_name", "contact_phone", "address",
          "credit_limit", "status", "create_time", "update_time", "updated_by_id", "updated_by_name",
          "remark", "version"],
         [[c.id, c.code, c.name, c.contact, c.phone, c.address, c.credit_limit, 1,
           "2025-10-15 09:00:00", "2025-10-15 09:00:00", Raw("@demo_actor"), Raw("@demo_actor_name"),
           "演示客户，月结合作", 0] for c in customers])

    emit("product",
         ["id", "product_code", "product_name", "category_id", "brand_name", "unit_name",
          "quantity_precision", "specification", "barcode", "reference_purchase_price",
          "reference_sale_price", "safety_stock_qty", "status", "create_time", "update_time",
          "remark", "version"],
         [[p.id, p.code, p.name, p.category_id, p.brand, p.unit, 0, p.specification, None,
           p.price_cents, p.sale_price_cents,
           p.safety_stock * SCALE, 1, "2025-10-15 09:00:00", "2025-10-15 09:00:00", "", 0]
          for p in products])

    sp_rows = []
    for product_relations in relations.values():
        for relation in product_relations:
            sp_rows.append([
                relation.id, relation.supplier_id, relation.product_id,
                relation.latest_purchase_price, relation.quote_cents, "年度框架协议报价",
                "2026-10-01 10:00:00", relation.quote_valid_until,
                qty_to_stored(relation.min_order_qty),
                relation.quality_score, relation.price_score, relation.recommend_score,
                relation.last_purchase_at, None, relation.score_basis_amount, relation.score_status,
                relation.status,
                "2025-10-20 09:00:00", "2026-10-01 10:00:00", None, "",
                "" if relation.kind == "PRIMARY" else ("备选低成本供应商" if relation.kind == "LOW_COST" else "备选优质供应商"),
                0,
            ])
    emit("supplier_product",
         ["id", "supplier_id", "product_id", "latest_purchase_price", "quoted_purchase_price",
          "quoted_price_reason", "quoted_price_updated_at", "quote_valid_until", "min_order_qty",
          "quality_score", "price_score", "recommend_score", "last_purchase_at",
          "avg_delivery_days", "score_basis_amount", "score_status", "status",
          "create_time", "update_time", "updated_by_id", "updated_by_name", "remark", "version"],
         sp_rows)

    return chunks


@dataclass
class StockMove:
    """一次库存变动，用于生成 stock_bill 与 warehouse_stock。"""
    ts: datetime
    warehouse_index: int
    product: Product
    change: int
    bill_type: str
    entry_mode: str
    source_bill_id: int | None
    source_no: str
    source_item_id: int | None = None
    work_bill_id: int = 0
    work_item_id: int = 0
    qualified_qty: int = 0
    defective_qty: int = 0
    stock_bill_item_id: int | None = None


@dataclass
class SimulationResult:
    purchase_orders: list[PurchaseOrder]
    sales_orders: list[SalesOrder]
    inbound_bills: list[InboundBill]
    outbound_bills: list[OutboundBill]
    return_orders: list[ReturnOrder]
    moves: list[StockMove]
    adjust_in_bills: list[InboundBill] = field(default_factory=list)
    transfer_out_bills: list[OutboundBill] = field(default_factory=list)
    transfer_in_bills: list[InboundBill] = field(default_factory=list)


def day_range(start: date, end: date):
    cursor = start
    while cursor <= end:
        yield cursor
        cursor += timedelta(days=1)


def daily_demand(product: Product, day: date) -> float:
    """按月份季节系数计算日均销量。"""
    month = day.month
    return product.monthly_demand * product.seasonal.get(month, 1.0) / 30.0


def pick_supplier_relation(relations: list[SupplyRelation], day: date | None = None) -> SupplyRelation:
    """按主源/备选角色加权选择供货关系；新开发供货关系不产生历史采购。"""
    active = [r for r in relations if r.status == 1 and r.has_history]
    if not active:
        active = [r for r in relations if r.status == 1] or relations
    weights = []
    for relation in active:
        if relation.kind == "PRIMARY":
            weight = 0.70
        elif relation.kind == "LOW_COST":
            weight = 0.18
        else:
            weight = 0.12
        # 近 180 天无采购样本的供应商降低被选中概率，保留少量新合作。
        if day is not None and day >= TODAY - timedelta(days=180) and not relation.supplier.has_recent_purchase:
            weight *= 0.03
        weights.append(weight)
    return weighted_pick(active, weights)


def create_inbound_for_purchase(order: PurchaseOrder, bill: InboundBill) -> None:
    order.inbound_bills.append(bill)


def simulate(products: list[Product], relations: dict[int, list[SupplyRelation]],
             warehouses: list[Warehouse], customers: list[Customer]) -> SimulationResult:
    """按日模拟采购、销售、库存与未完成单据。"""
    po_id = IdGen(ID_PURCHASE_ORDER)
    poi_id = IdGen(ID_PURCHASE_ITEM)
    so_id = IdGen(ID_SALES_ORDER)
    soi_id = IdGen(ID_SALES_ITEM)
    ib_id = IdGen(ID_INBOUND_BILL)
    ibi_id = IdGen(ID_INBOUND_ITEM)
    ob_id = IdGen(ID_OUTBOUND_BILL)
    obi_id = IdGen(ID_OUTBOUND_ITEM)
    adj_in_bill_id = IdGen(ID_ADJUST_IN_BILL)
    adj_in_item_id = IdGen(ID_ADJUST_IN_ITEM)
    tr_out_bill_id = IdGen(ID_TRANSFER_OUT_BILL)
    tr_out_item_id = IdGen(ID_TRANSFER_OUT_ITEM)
    tr_in_bill_id = IdGen(ID_TRANSFER_IN_BILL)
    tr_in_item_id = IdGen(ID_TRANSFER_IN_ITEM)

    po_no = DailyNoGen("PO")
    so_no = DailyNoGen("SO")
    ib_no = DailyNoGen("IB")
    ob_no = DailyNoGen("OB")

    stock: dict[tuple[int, int], int] = {}
    moves: list[StockMove] = []
    purchase_orders: list[PurchaseOrder] = []
    sales_orders: list[SalesOrder] = []
    inbound_bills: list[InboundBill] = []
    outbound_bills: list[OutboundBill] = []
    adjust_in_bills: list[InboundBill] = []

    product_by_id = {p.id: p for p in products}
    products_by_warehouse = {index: [p for p in products if p.warehouses[0] == index]
                             for index in range(len(warehouses))}
    relations_by_supplier: dict[int, list[SupplyRelation]] = {}
    for product_relations in relations.values():
        for relation in product_relations:
            relations_by_supplier.setdefault(relation.supplier_id, []).append(relation)
    in_transit: set[int] = set()
    arrivals: dict[date, list[tuple[PurchaseOrder, PurchaseLine, int]]] = {}
    # 每个商品分配期末目标：zero 10%、low 20%、high 15%、normal 55%。
    target_roll = {p.id: rng.random() for p in products}

    def stock_key(wi: int, pid: int) -> tuple[int, int]:
        return wi, pid

    def get_stock(wi: int, pid: int) -> int:
        return stock.get(stock_key(wi, pid), 0)

    def add_stock(wi: int, pid: int, delta: int) -> None:
        stock[stock_key(wi, pid)] = max(0, get_stock(wi, pid) + delta)

    # 1. 期初建账：每个仓库一张库存调整入库单，覆盖本仓商品。
    for wi, warehouse in enumerate(warehouses):
        bill = InboundBill(
            id=adj_in_bill_id.next(),
            no=ib_no.next(WINDOW_START - timedelta(days=1)),
            order=None,
            status="CONFIRMED",
            created_at=random_datetime(WINDOW_START - timedelta(days=1), 9, 17),
            confirmed_at=random_datetime(WINDOW_START, 8, 10),
            lines=[],
            bill_type="ADJUST_IN",
            entry_mode="MANUAL_ADJUSTMENT",
            source_type="STOCK_ADJUST",
        )
        for product in products:
            if wi not in product.warehouses:
                continue
            initial = int(product.safety_stock * 1.2 + daily_demand(product, WINDOW_START) * 28)
            initial = max(10, initial)
            add_stock(wi, product.id, initial)
            line = {
                "id": adj_in_item_id.next(),
                "product": product,
                "quantity": initial,
                "unit_price": 0,
                "qualified_qty": 0,
                "defective_qty": 0,
                "current_qty": initial,
                "plan_qty": initial,
                "processed_qty": 0,
                "pending_qty": 0,
                "source_item_id": None,
                "stock_bill_item_id": None,
            }
            bill.lines.append(line)
            moves.append(StockMove(
                ts=bill.confirmed_at,
                warehouse_index=wi,
                product=product,
                change=initial,
                bill_type="ADJUST_IN",
                entry_mode="MANUAL_ADJUSTMENT",
                source_bill_id=None,
                source_no="",
                work_bill_id=bill.id,
                work_item_id=line["id"],
                qualified_qty=0,
                defective_qty=0,
            ))
        adjust_in_bills.append(bill)
        inbound_bills.append(bill)

    # 2. 按日模拟。
    pending_purchase_lines: dict[tuple[int, date], list[tuple[PurchaseOrder, PurchaseLine, int]]] = {}
    for day in day_range(WINDOW_START, WINDOW_END):
        # 2.1 处理当天到货：只有到期日等于当天的批次才入库。
        for order, line, qty in arrivals.pop(day, []):
            if order.status == "CANCELLED":
                continue
            line.inbound_qty += qty
            # 入库质检：按供应商质量水平生成次品数量。
            defective = 0
            if line.relation.supplier.quality_factor < 0.999:
                defective = round(qty * (1 - line.relation.supplier.quality_factor) * rng.uniform(0.3, 1.0))
                defective = min(defective, max(0, qty // 4))
            qualified = qty - defective
            line.batches.append((qty, day, qualified, defective))
            add_stock(order.warehouse_index, line.relation.product_id, qty)
            # 到货后解除在途标记，允许下一轮补货。
            in_transit.discard(line.relation.product_id)
            if line.inbound_qty >= line.quantity:
                order.fully_received_at = random_datetime(day, 9, 18)

        # 2.2 销售出库：按客户与仓库合并成单。
        demand_buckets: dict[tuple[int, int], list[tuple[Product, int]]] = {}
        reserved: dict[tuple[int, int], int] = {}
        for customer in customers:
            # 每个客户每天约 8% 概率下单，购物篮 1-5 个商品，形成多行销售单。
            if rng.random() >= 0.08:
                continue
            pool = products_by_warehouse.get(customer.warehouse_index, [])
            if not pool:
                continue
            basket_size = weighted_pick([1, 2, 3, 4, 5], [0.40, 0.27, 0.16, 0.11, 0.06])
            basket = rng.sample(pool, min(basket_size, len(pool)))
            for product in basket:
                daily = daily_demand(product, day)
                target = target_roll[product.id]
                # 积压商品在最后两个月减少销售，其余商品照常。
                if target >= 0.85 and day >= date(2026, 9, 1) and rng.random() < 0.35:
                    continue
                # B2B 订单通常一次覆盖 3-6 周销量，少量客户按周小批量补货。
                coverage_days = rng.uniform(18, 48) if rng.random() < 0.75 else rng.uniform(5, 15)
                # 低库存目标商品在末期加大销售，形成安全库存以下的演示样本。
                if target < 0.30:
                    coverage_days *= 1.35
                qty = max(1, round(daily * coverage_days))
                # 同一天多张销售单按预留量扣减，避免同日超卖。
                key_stock = (product.warehouses[0], product.id)
                available = get_stock(*key_stock) - reserved.get(key_stock, 0)
                if available <= 0:
                    continue
                qty = min(qty, available)
                reserved[key_stock] = reserved.get(key_stock, 0) + qty
                key = (customer.id, customer.warehouse_index)
                demand_buckets.setdefault(key, []).append((product, qty))
        for (customer_id, wi), bucket in demand_buckets.items():
            customer = next(c for c in customers if c.id == customer_id)
            # 购物篮最多 5 行，超出拆成多张单。
            chunk_size = 5
            for start in range(0, len(bucket), chunk_size):
                chunk = bucket[start:start + chunk_size]
                created = day - timedelta(days=rng.randint(0, 2))
                expected_delivery = day + timedelta(days=rng.randint(1, 5))
                lines: list[SalesLine] = []
                total = 0
                for product, qty in chunk:
                    # 客户等级与促销共同决定成交价。
                    if customer.credit_limit >= 500000 * SCALE:
                        factor = rng.uniform(0.87, 0.94)
                    elif rng.random() < 0.12:
                        factor = rng.uniform(0.75, 0.88)
                    else:
                        factor = rng.uniform(0.94, 1.0)
                    unit_price = max(1, round(product.sale_price_cents * factor))
                    line_total = qty * unit_price
                    lines.append(SalesLine(
                        item_id=soi_id.next(),
                        product=product,
                        quantity=qty,
                        unit_price=unit_price,
                        total_amount=line_total,
                    ))
                    total += line_total
                order = SalesOrder(
                    id=so_id.next(),
                    no=so_no.next(created),
                    customer=customer,
                    warehouse_index=wi,
                    status="OUTBOUND_DONE",
                    created_at=random_datetime(created, 9, 18),
                    expected_delivery=expected_delivery,
                    submitted_at=random_datetime(day, 6, 7),
                    approved_at=random_datetime(day, 7, 8),
                    locked_at=random_datetime(day, 6, 7),
                    lines=lines,
                    outbound_bills=[],
                )
                for line in lines:
                    line.locked_qty = line.quantity
                sales_orders.append(order)
                # 部分出库：约 3% 的销售单只确认部分数量，剩余保持待确认。
                partial = rng.random() < 0.03
                out_qty_by_line = []
                for line in lines:
                    qty = line.quantity if not partial else max(1, round(line.quantity * rng.uniform(0.4, 0.8)))
                    out_qty_by_line.append(qty)
                # 出库确认时间不晚于当天，保证库存先入后出。
                out_ts = random_datetime(day, 10, 18)
                out_lines = []
                for line, qty in zip(lines, out_qty_by_line):
                    out_lines.append({
                        "id": obi_id.next(),
                        "product": line.product,
                        "quantity": line.quantity,
                        "current_qty": qty,
                        "processed_qty": 0,
                        "pending_qty": line.quantity - qty,
                        "unit_price": line.unit_price,
                        "source_item_id": line.item_id,
                        "stock_bill_item_id": None,
                    })
                    line.outbound_qty = qty
                    if partial:
                        # 部分出库后，剩余未出库数量继续锁定。
                        line.locked_qty = line.quantity - qty
                    else:
                        # 全部出库后不再占用锁定量。
                        line.locked_qty = 0
                    add_stock(wi, line.product.id, -qty)
                    moves.append(StockMove(
                        ts=out_ts,
                        warehouse_index=wi,
                        product=line.product,
                        change=-qty,
                        bill_type="SALES_OUT",
                        entry_mode="SOURCE_GENERATED",
                        source_bill_id=order.id,
                        source_no=order.no,
                        source_item_id=out_lines[-1]["source_item_id"],
                        work_bill_id=0,
                        work_item_id=out_lines[-1]["id"],
                        qualified_qty=0,
                        defective_qty=0,
                    ))
                out_bill = OutboundBill(
                    id=ob_id.next(),
                    no=ob_no.next(day),
                    order=order,
                    status="CONFIRMED",
                    created_at=random_datetime(day, 9, 16),
                    confirmed_at=out_ts,
                    lines=out_lines,
                )
                # 回填本张出库单对应的库存流水凭证 ID。
                for move in moves[-len(out_lines):]:
                    move.work_bill_id = out_bill.id
                order.outbound_bills.append(out_bill)
                outbound_bills.append(out_bill)
                if partial:
                    order.status = "PARTIAL_OUTBOUND"
                    # 剩余数量生成一张待确认出库单，并保持锁定。
                    rest_lines = []
                    for line, qty in zip(lines, out_qty_by_line):
                        remaining = line.quantity - qty
                        if remaining <= 0:
                            continue
                        rest_lines.append({
                            "id": obi_id.next(),
                            "product": line.product,
                            "quantity": line.quantity,
                            "current_qty": 0,
                            "processed_qty": qty,
                            "pending_qty": remaining,
                            "unit_price": line.unit_price,
                            "source_item_id": line.item_id,
                            "stock_bill_item_id": None,
                        })
                    if rest_lines:
                        rest_bill = OutboundBill(
                            id=ob_id.next(),
                            no=ob_no.next(day),
                            order=order,
                            status="PENDING_CONFIRM",
                            created_at=random_datetime(day, 16, 19),
                            confirmed_at=None,
                            lines=rest_lines,
                        )
                        order.outbound_bills.append(rest_bill)
                        outbound_bills.append(rest_bill)
                else:
                    order.status = "OUTBOUND_DONE"

        # 2.3 补货：库存低于再订货点时，按供应商合并生成采购单。
        replenish: dict[tuple[int, int], list[tuple[SupplyRelation, int]]] = {}
        for product in products:
            if product.id in in_transit:
                continue
            # 每 5 天集中检查一次补货；同一供应商的商品同一天检查，提升多行采购单比例。
            primary_supplier_id = relations[product.id][0].supplier_id
            if (primary_supplier_id + day.toordinal()) % 5 != 0:
                continue
            target = target_roll[product.id]
            wi = product.warehouses[0]
            current = get_stock(wi, product.id)
            daily = daily_demand(product, day)
            relation = pick_supplier_relation(relations[product.id], day)
            lead = max(2, relation.supplier.lead_days)
            reorder_point = product.safety_stock * 1.5 + daily * (lead + 5)
            factor = 1.0
            if target < 0.10:              # 期末目标零库存
                if day >= date(2026, 8, 15):
                    continue
            elif target < 0.30:            # 期末目标低库存
                if day >= date(2026, 10, 1):
                    continue
                factor = 0.55
            elif target >= 0.85:           # 期末目标积压
                factor = 2.4
                # 最后两个月每月一次强制补货，形成可演示的积压水位。
                if day >= date(2026, 9, 1) and day.day == 1:
                    qty = daily * 30 * 1.5
                    qty = max(qty, relation.min_order_qty, 1)
                    qty = int(round(qty / max(1, relation.min_order_qty)) * max(1, relation.min_order_qty))
                    replenish.setdefault((relation.supplier_id, wi), []).append((relation, qty))
                    continue
            if current >= reorder_point:
                continue
            qty = daily * (lead + rng.randint(18, 32)) * factor
            qty = max(qty, relation.min_order_qty, 1)
            qty = int(round(qty / max(1, relation.min_order_qty)) * max(1, relation.min_order_qty))
            qty = max(1, min(qty, int(product.monthly_demand * relation.supplier.scale * 1.6)))
            replenish.setdefault((relation.supplier_id, wi), []).append((relation, qty))

        for (supplier_id, wi), items in replenish.items():
            # 同一供应商同仓的其他低库存商品并入本单，形成多行采购。
            existing = {relation.product_id for relation, _qty in items}
            taken_elsewhere = {relation.product_id for bucket in replenish.values()
                               for relation, _qty in bucket}
            for candidate in relations_by_supplier.get(supplier_id, []):
                if len(items) >= 4:
                    break
                if candidate.status != 1 or not candidate.has_history:
                    continue
                if candidate.product_id in existing or candidate.product_id in taken_elsewhere:
                    continue
                if candidate.product_id in in_transit:
                    continue
                if candidate.product.warehouses[0] != wi:
                    continue
                current = get_stock(wi, candidate.product_id)
                daily = daily_demand(candidate.product, day)
                if current < daily * 40:
                    extra_qty = max(candidate.min_order_qty, round(daily * 30))
                    items.append((candidate, extra_qty))
                    existing.add(candidate.product_id)
            # 同供应商同仓库当天合并为一张采购单，最多 4 行。
            chunk_size = 4
            for start in range(0, len(items), chunk_size):
                chunk = items[start:start + chunk_size]
                supplier = chunk[0][0].supplier
                created = day - timedelta(days=rng.randint(1, 3))
                approved = day
                order_id = po_id.next()
                order_no = po_no.next(created)
                lines = []
                total = 0
                for relation, qty in chunk:
                    # 少量订单低于 MOQ，用于演示技能对起订量的识别。
                    if rng.random() < 0.06:
                        qty = max(1, int(qty * rng.uniform(0.35, 0.8)))
                    price = relation.quote_cents
                    if rng.random() < 0.12:
                        price = max(1, round(price * rng.uniform(0.97, 1.03)))
                    line = PurchaseLine(
                        item_id=poi_id.next(),
                        relation=relation,
                        quantity=qty,
                        unit_price=price,
                        total_amount=qty * price,
                        selected_score=relation.recommend_score,
                    )
                    lines.append(line)
                    total += line.total_amount
                    in_transit.add(relation.product_id)
                expected = approved + timedelta(days=supplier.lead_days + rng.randint(-supplier.lead_jitter, supplier.lead_jitter))
                expected = max(expected, approved + timedelta(days=2))
                # 承诺日固定，实际到货按准时率决定是否逾期，逾期天数体现在交付分。
                actual = expected
                if rng.random() > supplier.on_time_rate:
                    actual = expected + timedelta(days=rng.randint(1, 15))
                order = PurchaseOrder(
                    id=order_id,
                    no=order_no,
                    supplier=supplier,
                    warehouse_index=wi,
                    status="APPROVED",
                    created_at=random_datetime(created, 9, 17),
                    expected_arrival=expected,
                    submitted_at=random_datetime(created, 9, 17),
                    approved_at=random_datetime(approved, 6, 7),
                    fully_received_at=None,
                    cancelled_at=None,
                    cancel_reason="",
                    cancel_affects_score=0,
                    lines=lines,
                )
                purchase_orders.append(order)
                for line in lines:
                    arrivals.setdefault(actual, []).append((order, line, line.quantity))

    # 3. 处理仍在途的采购单：到货日已过但未到货的按部分入库处理；
    #    到货日未到的保留为在途。
    for order in purchase_orders:
        remaining = [line for line in order.lines if line.inbound_qty < line.quantity]
        if not remaining:
            continue
        # 到货日已过但未到货：视为部分入库或逾期在途。
        if order.expected_arrival <= WINDOW_END:
            if rng.random() < 0.55:
                order.status = "PARTIAL_INBOUND"
            else:
                order.status = "APPROVED"
        else:
            order.status = "APPROVED"

    # 4. 生成采购入库单：已到货批次拆成确认单，剩余量生成待确认单。
    for order in purchase_orders:
        # 草稿、待审核、已取消的采购单不会生成入库单。
        if order.status in ("DRAFT", "SUBMITTED", "CANCELLED"):
            continue
        by_day: dict[date, list[tuple[PurchaseLine, int, int, int]]] = {}
        for line in order.lines:
            for qty, day, qualified, defective in line.batches:
                by_day.setdefault(day, []).append((line, qty, qualified, defective))
        processed: dict[int, int] = {}
        for day in sorted(by_day):
            lines = []
            for line, qty, qualified, defective in by_day[day]:
                before = processed.get(line.item_id, 0)
                lines.append({
                    "id": ibi_id.next(),
                    "product": line.relation.product,
                    "quantity": line.quantity,
                    "current_qty": qty,
                    "processed_qty": before,
                    "pending_qty": line.quantity - before - qty,
                    "unit_price": line.unit_price,
                    "source_item_id": line.item_id,
                    "qualified_qty": qualified,
                    "defective_qty": defective,
                    "stock_bill_item_id": None,
                })
                processed[line.item_id] = before + qty
            bill = InboundBill(
                id=ib_id.next(),
                no=ib_no.next(day - timedelta(days=1)),
                order=order,
                status="CONFIRMED",
                created_at=random_datetime(day - timedelta(days=1), 9, 16),
                confirmed_at=random_datetime(day, 8, 9),
                lines=lines,
            )
            inbound_bills.append(bill)
            create_inbound_for_purchase(order, bill)
            for item in lines:
                moves.append(StockMove(
                    ts=bill.confirmed_at,
                    warehouse_index=order.warehouse_index,
                    product=item["product"],
                    change=item["current_qty"],
                    bill_type="PURCHASE_IN",
                    entry_mode="SOURCE_GENERATED",
                    source_bill_id=order.id,
                    source_no=order.no,
                    source_item_id=item["source_item_id"],
                    work_bill_id=bill.id,
                    work_item_id=item["id"],
                    qualified_qty=item["qualified_qty"],
                    defective_qty=item["defective_qty"],
                ))
        # 剩余未入库数量：生成待确认入库单（在途或部分入库）。
        remaining_lines = []
        for line in order.lines:
            done = processed.get(line.item_id, 0)
            if done < line.quantity:
                remaining_lines.append({
                    "id": ibi_id.next(),
                    "product": line.relation.product,
                    "quantity": line.quantity,
                    "plan_qty": line.quantity,
                    "current_qty": 0,
                    "processed_qty": done,
                    "pending_qty": line.quantity - done,
                    "unit_price": line.unit_price,
                    "source_item_id": line.item_id,
                    "qualified_qty": 0,
                    "defective_qty": 0,
                    "stock_bill_item_id": None,
                })
        if remaining_lines:
            created_day = WINDOW_END - timedelta(days=rng.randint(1, 5))
            bill = InboundBill(
                id=ib_id.next(),
                no=ib_no.next(created_day),
                order=order,
                status="PENDING_CONFIRM",
                created_at=random_datetime(created_day, 9, 16),
                confirmed_at=None,
                lines=remaining_lines,
            )
            inbound_bills.append(bill)
            create_inbound_for_purchase(order, bill)
        if order.lines and all(line.inbound_qty >= line.quantity for line in order.lines):
            order.status = "INBOUND_DONE"
            if order.fully_received_at is None:
                order.fully_received_at = max(
                    (line.batches[-1][1] for line in order.lines if line.batches), default=WINDOW_END)
                order.fully_received_at = random_datetime(order.fully_received_at, 9, 18)
        elif any(line.inbound_qty > 0 for line in order.lines):
            order.status = "PARTIAL_INBOUND"
        else:
            order.status = "APPROVED"

    # 5. 未完成销售单：最近 30 天生成草稿、待审核、已审核单据。
    pending_locked: dict[tuple[int, int], int] = {}
    for day in day_range(WINDOW_END - timedelta(days=30), WINDOW_END):
        for _ in range(rng.randint(3, 8)):
            product = rng.choice(products)
            wi = product.warehouses[0]
            key = (wi, product.id)
            available = get_stock(wi, product.id) - pending_locked.get(key, 0)
            if available <= 0:
                continue
            daily = daily_demand(product, day)
            qty = max(1, min(round(daily * rng.uniform(1.0, 4.0)), available))
            customer = rng.choice(customers)
            factor = rng.uniform(0.88, 1.0)
            unit_price = max(1, round(product.sale_price_cents * factor))
            status = weighted_pick(["DRAFT", "SUBMITTED", "APPROVED"], [0.45, 0.30, 0.25])
            created = day - timedelta(days=rng.randint(0, 3))
            expected_delivery = max(TODAY + timedelta(days=1), day + timedelta(days=rng.randint(2, 15)))
            order = SalesOrder(
                id=so_id.next(),
                no=so_no.next(created),
                customer=customer,
                warehouse_index=wi,
                status=status,
                created_at=random_datetime(created, 9, 18),
                expected_delivery=expected_delivery,
                submitted_at=random_datetime(created, 9, 18) if status != "DRAFT" else None,
                approved_at=random_datetime(created, 9, 18) if status == "APPROVED" else None,
                locked_at=random_datetime(created, 9, 18) if status != "DRAFT" else None,
                lines=[SalesLine(
                    item_id=soi_id.next(),
                    product=product,
                    quantity=qty,
                    unit_price=unit_price,
                    total_amount=qty * unit_price,
                    locked_qty=qty if status != "DRAFT" else 0,
                )],
                outbound_bills=[],
            )
            if status == "APPROVED":
                # 已审核销售单生成一张待确认出库单。
                out_lines = [{
                    "id": obi_id.next(),
                    "product": product,
                    "quantity": qty,
                    "current_qty": 0,
                    "processed_qty": 0,
                    "pending_qty": qty,
                    "unit_price": unit_price,
                    "source_item_id": order.lines[0].item_id,
                    "stock_bill_item_id": None,
                }]
                bill = OutboundBill(
                    id=ob_id.next(),
                    no=ob_no.next(created),
                    order=order,
                    status="PENDING_CONFIRM",
                    created_at=random_datetime(created, 9, 18),
                    confirmed_at=None,
                    lines=out_lines,
                )
                order.outbound_bills.append(bill)
                outbound_bills.append(bill)
            if status != "DRAFT":
                pending_locked[key] = pending_locked.get(key, 0) + qty
            sales_orders.append(order)

    # 6. 少量采购草稿与取消单，覆盖状态演示。
    for _ in range(40):
        product = rng.choice(products)
        relation = pick_supplier_relation(relations[product.id], WINDOW_END)
        wi = product.warehouses[0]
        created = WINDOW_END - timedelta(days=rng.randint(0, 20))
        qty = max(1, round(daily_demand(product, created) * rng.randint(3, 10)))
        line = PurchaseLine(
            item_id=poi_id.next(),
            relation=relation,
            quantity=qty,
            unit_price=relation.quote_cents,
            total_amount=qty * relation.quote_cents,
        )
        order = PurchaseOrder(
            id=po_id.next(),
            no=po_no.next(created),
            supplier=relation.supplier,
            warehouse_index=wi,
            status=rng.choice(["DRAFT", "SUBMITTED", "CANCELLED"]),
            created_at=random_datetime(created, 9, 17),
            expected_arrival=TODAY + timedelta(days=rng.randint(3, 20)),
            submitted_at=random_datetime(created, 9, 17) if rng.random() < 0.6 else None,
            approved_at=None,
            fully_received_at=None,
            cancelled_at=random_datetime(created, 9, 17) if rng.random() < 0.3 else None,
            cancel_reason="供应商无法按时供货" if rng.random() < 0.3 else "",
            cancel_affects_score=1 if rng.random() < 0.5 else 0,
            lines=[line],
        )
        purchase_orders.append(order)

    # 7. 跨仓调拨：辅仓部分库存调到主仓，生成成对的调整出/入库单。
    transfer_out_bills: list[OutboundBill] = []
    transfer_in_bills: list[InboundBill] = []
    for product in products:
        if len(product.warehouses) < 2:
            continue
        if rng.random() > 0.35:
            continue
        source_wi = product.warehouses[1]
        target_wi = product.warehouses[0]
        available = get_stock(source_wi, product.id)
        if available < 20:
            continue
        qty = max(1, int(available * rng.uniform(0.2, 0.5)))
        day = WINDOW_START + timedelta(days=rng.randint(30, 300))
        ts = random_datetime(day, 10, 17)
        out_bill = OutboundBill(
            id=tr_out_bill_id.next(),
            no=ob_no.next(day),
            order=None,
            status="CONFIRMED",
            created_at=ts,
            confirmed_at=ts,
            lines=[{
                "id": tr_out_item_id.next(),
                "product": product,
                "quantity": qty,
                "current_qty": qty,
                "processed_qty": 0,
                "pending_qty": 0,
                "unit_price": 0,
                "source_item_id": None,
                "stock_bill_item_id": None,
            }],
            bill_type="ADJUST_OUT",
            entry_mode="MANUAL_ADJUSTMENT",
            source_type="STOCK_ADJUST",
        )
        in_bill = InboundBill(
            id=tr_in_bill_id.next(),
            no=ib_no.next(day),
            order=None,
            status="CONFIRMED",
            created_at=ts,
            confirmed_at=ts,
            lines=[{
                "id": tr_in_item_id.next(),
                "product": product,
                "quantity": qty,
                "current_qty": qty,
                "processed_qty": 0,
                "pending_qty": 0,
                "unit_price": 0,
                "source_item_id": None,
                "qualified_qty": 0,
                "defective_qty": 0,
                "stock_bill_item_id": None,
            }],
            bill_type="ADJUST_IN",
            entry_mode="MANUAL_ADJUSTMENT",
            source_type="STOCK_ADJUST",
        )
        add_stock(source_wi, product.id, -qty)
        add_stock(target_wi, product.id, qty)
        moves.append(StockMove(ts=ts, warehouse_index=source_wi, product=product, change=-qty,
                               bill_type="ADJUST_OUT", entry_mode="MANUAL_ADJUSTMENT",
                               source_bill_id=None, source_no="",
                               work_bill_id=out_bill.id, work_item_id=out_bill.lines[0]["id"]))
        moves.append(StockMove(ts=ts + timedelta(seconds=1), warehouse_index=target_wi, product=product,
                               change=qty, bill_type="ADJUST_IN", entry_mode="MANUAL_ADJUSTMENT",
                               source_bill_id=None, source_no="",
                               work_bill_id=in_bill.id, work_item_id=in_bill.lines[0]["id"]))
        transfer_out_bills.append(out_bill)
        transfer_in_bills.append(in_bill)
        outbound_bills.append(out_bill)
        inbound_bills.append(in_bill)

    # 8. 采购退货：从已完成采购单中挑选，按 5%-20% 退货。
    return_orders: list[ReturnOrder] = []
    return_id = IdGen(ID_RETURN_ORDER)
    return_item_id = IdGen(ID_RETURN_ITEM)
    pr_no = DailyNoGen("PR")
    sr_no = DailyNoGen("SR")
    candidates = [o for o in purchase_orders if o.status == "INBOUND_DONE" and o.lines]
    rng.shuffle(candidates)
    for order in candidates[:220]:
        product = order.lines[0].relation.product
        wi = order.warehouse_index
        available = get_stock(wi, product.id)
        if available < 10:
            continue
        if rng.random() > 0.6:
            continue
        qty = max(1, min(int(available * rng.uniform(0.03, 0.12)), available // 2))
        line_total = qty * order.lines[0].unit_price
        status = weighted_pick(
            ["DRAFT", "SUBMITTED", "APPROVED", "PARTIAL_EXECUTED", "COMPLETED", "CANCELLED"],
            [0.45, 0.15, 0.12, 0.08, 0.15, 0.05])
        created_day = order.fully_received_at.date() + timedelta(days=rng.randint(5, 45)) if order.fully_received_at else WINDOW_END
        created_day = max(WINDOW_START, min(created_day, WINDOW_END - timedelta(days=2)))
        expected = max(TODAY + timedelta(days=rng.randint(2, 20)), created_day + timedelta(days=5))
        ret = ReturnOrder(
            id=return_id.next(),
            no=pr_no.next(created_day),
            return_type="PURCHASE_RETURN",
            source_order_id=order.id,
            source_order_no=order.no,
            party_id=order.supplier.id,
            party_code=order.supplier.code,
            party_name=order.supplier.name,
            warehouse_index=wi,
            expected_execution_date=expected,
            status=status,
            created_at=random_datetime(created_day, 9, 17),
            submitted_at=random_datetime(created_day, 9, 17) if status != "DRAFT" else None,
            approved_at=random_datetime(created_day, 9, 17) if status in ("APPROVED", "PARTIAL_EXECUTED", "COMPLETED") else None,
            lines=[{
                "id": return_item_id.next(),
                "product": product,
                "source_item_id": order.lines[0].item_id,
                "source_fulfilled_qty": order.lines[0].inbound_qty,
                "requested_qty": qty,
                "approved_qty": qty if status in ("APPROVED", "PARTIAL_EXECUTED", "COMPLETED") else 0,
                "processed_qty": 0,
                "unit_price": order.lines[0].unit_price,
                "total_amount": line_total,
            }],
            total_amount=line_total,
            reason_code="QUALITY",
            return_reason="到货抽检发现批次质量问题",
            handling_type="REFUND",
        )
        return_orders.append(ret)

    # 9. 销售退货：从已完成销售单中挑选，按 3%-10% 退货。
    sales_candidates = [o for o in sales_orders if o.status in ("OUTBOUND_DONE", "PARTIAL_OUTBOUND") and o.lines]
    rng.shuffle(sales_candidates)
    for order in sales_candidates[:260]:
        line = order.lines[0]
        product = line.product
        wi = order.warehouse_index
        status = weighted_pick(
            ["DRAFT", "SUBMITTED", "APPROVED", "PARTIAL_EXECUTED", "COMPLETED", "CANCELLED"],
            [0.45, 0.15, 0.12, 0.08, 0.15, 0.05])
        qty = max(1, int(line.outbound_qty * rng.uniform(0.03, 0.10)))
        if qty < 1:
            continue
        line_total = qty * line.unit_price
        created_day = order.approved_at.date() + timedelta(days=rng.randint(5, 60)) if order.approved_at else WINDOW_END
        created_day = max(WINDOW_START, min(created_day, WINDOW_END - timedelta(days=2)))
        expected = max(TODAY + timedelta(days=rng.randint(2, 20)), created_day + timedelta(days=5))
        ret = ReturnOrder(
            id=return_id.next(),
            no=sr_no.next(created_day),
            return_type="SALES_RETURN",
            source_order_id=order.id,
            source_order_no=order.no,
            party_id=order.customer.id,
            party_code=order.customer.code,
            party_name=order.customer.name,
            warehouse_index=wi,
            expected_execution_date=expected,
            status=status,
            created_at=random_datetime(created_day, 9, 17),
            submitted_at=random_datetime(created_day, 9, 17) if status != "DRAFT" else None,
            approved_at=random_datetime(created_day, 9, 17) if status in ("APPROVED", "PARTIAL_EXECUTED", "COMPLETED") else None,
            lines=[{
                "id": return_item_id.next(),
                "product": product,
                "source_item_id": line.item_id,
                "source_fulfilled_qty": line.outbound_qty,
                "requested_qty": qty,
                "approved_qty": qty if status in ("APPROVED", "PARTIAL_EXECUTED", "COMPLETED") else 0,
                "processed_qty": 0,
                "unit_price": line.unit_price,
                "total_amount": line_total,
            }],
            total_amount=line_total,
            reason_code="CUSTOMER_RETURN",
            return_reason="客户反馈包装破损，协商退货",
            handling_type="REFUND",
        )
        return_orders.append(ret)

    # 10. 退货执行：已完成状态生成出入库单与库存流水，已审核状态生成待确认单据。
    ret_in_bill_id = IdGen(ID_RETURN_IN_BILL)
    ret_in_item_id = IdGen(ID_RETURN_IN_ITEM)
    ret_out_bill_id = IdGen(ID_RETURN_OUT_BILL)
    ret_out_item_id = IdGen(ID_RETURN_OUT_ITEM)
    for ret in return_orders:
        if ret.status not in ("APPROVED", "PARTIAL_EXECUTED", "COMPLETED"):
            continue
        line = ret.lines[0]
        product = line["product"]
        wi = ret.warehouse_index
        approved = line["approved_qty"]
        if approved <= 0:
            ret.status = "DRAFT"
            continue
        # 约 45% 的已审核退货单保留待执行状态，用于演示审核后未执行场景。
        keep_unexecuted = ret.status == "APPROVED" and rng.random() < 0.45
        exec_day = None if keep_unexecuted else ret.created_at.date() + timedelta(days=rng.randint(3, 30))
        if exec_day is not None and exec_day > WINDOW_END:
            exec_day = None
        exec_ts = random_datetime(exec_day, 10, 17) if exec_day else None
        # 采购退货会减少库存，统一安排在窗口最后一天晚间，保证不与其他出库冲突。
        if ret.return_type == "PURCHASE_RETURN" and not keep_unexecuted:
            exec_day = WINDOW_END
            exec_ts = random_datetime(WINDOW_END, 19, 20)
        # 采购退货需要仓库有足够库存，否则降级为待审核。
        if ret.return_type == "PURCHASE_RETURN" and not keep_unexecuted:
            available = get_stock(wi, product.id)
            if available <= 0:
                ret.status = "APPROVED"
                exec_day = None
                exec_ts = None
            elif approved > available:
                approved = available
                line["approved_qty"] = approved
        processed = 0
        if exec_day is None and ret.status in ("PARTIAL_EXECUTED", "COMPLETED"):
            # 执行日超出窗口时不能留下"已完成但无执行单"的退货。
            ret.status = "APPROVED"
        if exec_day is not None:
            if ret.status == "COMPLETED":
                processed = approved
            else:
                processed = max(1, int(approved * rng.uniform(0.3, 0.7)))
            if processed <= 0:
                processed = 0
                ret.status = "APPROVED"
                exec_day = None
            else:
                ret.status = "COMPLETED" if processed >= approved else "PARTIAL_EXECUTED"
        line["processed_qty"] = processed
        line["total_amount"] = line["requested_qty"] * line["unit_price"]
        ret.total_amount = line["total_amount"]

        if ret.return_type == "PURCHASE_RETURN":
            bill = OutboundBill(
                id=ret_out_bill_id.next(),
                no=ob_no.next(ret.created_at.date()),
                order=None,
                status="CONFIRMED" if processed > 0 else "PENDING_CONFIRM",
                created_at=random_datetime(ret.created_at.date(), 9, 17),
                confirmed_at=exec_ts,
                lines=[{
                    "id": ret_out_item_id.next(),
                    "product": product,
                    "quantity": approved,
                    "current_qty": processed,
                    "processed_qty": 0,
                    "pending_qty": approved - processed,
                    "unit_price": line["unit_price"],
                    "source_item_id": line["id"],
                    "stock_bill_item_id": None,
                }],
                bill_type="PURCHASE_RETURN",
                entry_mode="SOURCE_GENERATED",
                source_type="PURCHASE_RETURN_ORDER",
            )
            # 退货出库单的 source 字段在输出阶段按退货单填充。
            bill.return_order = ret
            ret.execution_bill = bill
            outbound_bills.append(bill)
            if processed > 0:
                add_stock(wi, product.id, -processed)
                moves.append(StockMove(
                    ts=bill.confirmed_at,
                    warehouse_index=wi,
                    product=product,
                    change=-processed,
                    bill_type="PURCHASE_RETURN",
                    entry_mode="SOURCE_GENERATED",
                    source_bill_id=ret.id,
                    source_no=ret.no,
                    source_item_id=line["id"],
                    work_bill_id=bill.id,
                    work_item_id=bill.lines[0]["id"],
                ))
        else:
            bill = InboundBill(
                id=ret_in_bill_id.next(),
                no=ib_no.next(ret.created_at.date()),
                order=None,
                status="CONFIRMED" if processed > 0 else "PENDING_CONFIRM",
                created_at=random_datetime(ret.created_at.date(), 9, 17),
                confirmed_at=exec_ts,
                lines=[{
                    "id": ret_in_item_id.next(),
                    "product": product,
                    "quantity": approved,
                    "current_qty": processed,
                    "processed_qty": 0,
                    "pending_qty": approved - processed,
                    "unit_price": line["unit_price"],
                    "source_item_id": line["id"],
                    "qualified_qty": processed,
                    "defective_qty": 0,
                    "stock_bill_item_id": None,
                }],
                bill_type="SALES_RETURN",
                entry_mode="SOURCE_GENERATED",
                source_type="SALES_RETURN_ORDER",
            )
            bill.return_order = ret
            ret.execution_bill = bill
            inbound_bills.append(bill)
            if processed > 0:
                add_stock(wi, product.id, processed)
                moves.append(StockMove(
                    ts=bill.confirmed_at,
                    warehouse_index=wi,
                    product=product,
                    change=processed,
                    bill_type="SALES_RETURN",
                    entry_mode="SOURCE_GENERATED",
                    source_bill_id=ret.id,
                    source_no=ret.no,
                    source_item_id=line["id"],
                    work_bill_id=bill.id,
                    work_item_id=bill.lines[0]["id"],
                    qualified_qty=processed,
                    defective_qty=0,
                ))

    # 11. 低库存目标商品在最后一周做清仓销售，形成安全库存以下的演示样本。
    for product in products:
        if target_roll[product.id] >= 0.30:
            continue
        wi = product.warehouses[0]
        need = get_stock(wi, product.id) - int(product.safety_stock * 0.6)
        if need <= 0:
            continue
        for _ in range(rng.randint(1, 3)):
            if need <= 0:
                break
            qty = min(need, max(1, int(need * rng.uniform(0.45, 0.85))))
            day = WINDOW_END
            created = day - timedelta(days=1)
            customer = rng.choice(customers)
            unit_price = max(1, round(product.sale_price_cents * rng.uniform(0.75, 0.90)))
            order = SalesOrder(
                id=so_id.next(),
                no=so_no.next(created),
                customer=customer,
                warehouse_index=wi,
                status="OUTBOUND_DONE",
                created_at=random_datetime(created, 9, 16),
                expected_delivery=day + timedelta(days=1),
                submitted_at=random_datetime(day, 6, 7),
                approved_at=random_datetime(day, 7, 8),
                locked_at=random_datetime(day, 6, 7),
                lines=[SalesLine(
                    item_id=soi_id.next(),
                    product=product,
                    quantity=qty,
                    unit_price=unit_price,
                    total_amount=qty * unit_price,
                    locked_qty=qty,
                    outbound_qty=qty,
                )],
                outbound_bills=[],
            )
            out_ts = random_datetime(day, 19, 20)
            out_bill = OutboundBill(
                id=ob_id.next(),
                no=ob_no.next(day),
                order=order,
                status="CONFIRMED",
                created_at=random_datetime(day, 9, 16),
                confirmed_at=out_ts,
                lines=[{
                    "id": obi_id.next(),
                    "product": product,
                    "quantity": qty,
                    "current_qty": qty,
                    "processed_qty": 0,
                    "pending_qty": 0,
                    "unit_price": unit_price,
                    "source_item_id": order.lines[0].item_id,
                    "stock_bill_item_id": None,
                }],
            )
            order.outbound_bills.append(out_bill)
            sales_orders.append(order)
            outbound_bills.append(out_bill)
            add_stock(wi, product.id, -qty)
            moves.append(StockMove(
                ts=out_ts,
                warehouse_index=wi,
                product=product,
                change=-qty,
                bill_type="SALES_OUT",
                entry_mode="SOURCE_GENERATED",
                source_bill_id=order.id,
                source_no=order.no,
                source_item_id=order.lines[0].item_id,
                work_bill_id=out_bill.id,
                work_item_id=out_bill.lines[0]["id"],
            ))
            need -= qty

    return SimulationResult(
        purchase_orders=purchase_orders,
        sales_orders=sales_orders,
        inbound_bills=inbound_bills,
        outbound_bills=outbound_bills,
        return_orders=return_orders,
        moves=moves,
        adjust_in_bills=adjust_in_bills,
        transfer_out_bills=transfer_out_bills,
        transfer_in_bills=transfer_in_bills,
    )


@dataclass
class StockBill:
    id: int
    no: str
    bill_type: str
    work_bill_id: int
    business_source_id: int | None
    business_source_no: str
    entry_mode: str
    warehouse_index: int
    confirmed_at: datetime
    lines: list[dict] = field(default_factory=list)


class Raw:
    """原样输出的 SQL 片段（如会话变量）。"""

    def __init__(self, text: str) -> None:
        self.text = text


def build_stock_bills(moves: list[StockMove], warehouses: list[Warehouse]) -> tuple[list[StockBill], dict[tuple[int, int], int]]:
    """按时间顺序生成库存流水凭证与明细，并计算最终库存。"""
    purchase_bill_id = IdGen(ID_PURCHASE_STOCK_BILL)
    purchase_item_id = IdGen(ID_PURCHASE_STOCK_ITEM)
    sales_bill_id = IdGen(ID_SALES_STOCK_BILL)
    sales_item_id = IdGen(ID_SALES_STOCK_ITEM)
    adjust_bill_id = IdGen(ID_ADJUST_STOCK_BILL)
    adjust_item_id = IdGen(ID_ADJUST_STOCK_ITEM)
    return_bill_id = IdGen(ID_RETURN_STOCK_BILL)
    return_item_id = IdGen(ID_RETURN_STOCK_ITEM)
    sl_no = DailyNoGen("SL")

    moves.sort(key=lambda m: (m.ts, m.work_bill_id, m.work_item_id))
    running: dict[tuple[int, int], int] = {}
    bills: list[StockBill] = []
    by_work: dict[int, StockBill] = {}
    for move in moves:
        key = (move.warehouse_index, move.product.id)
        before = running.get(key, 0)
        after = before + move.change
        if after < 0:
            raise ValueError(f"库存出现负数：商品 {move.product.code} 仓库 {move.warehouse_index} "
                             f"before={before} change={move.change} ts={move.ts}")
        running[key] = after
        if move.work_bill_id not in by_work:
            if move.bill_type == "PURCHASE_IN":
                bill_id_gen, item_id_gen = purchase_bill_id, purchase_item_id
            elif move.bill_type == "SALES_OUT":
                bill_id_gen, item_id_gen = sales_bill_id, sales_item_id
            elif move.bill_type in ("ADJUST_IN", "ADJUST_OUT"):
                bill_id_gen, item_id_gen = adjust_bill_id, adjust_item_id
            else:
                bill_id_gen, item_id_gen = return_bill_id, return_item_id
            bill = StockBill(
                id=bill_id_gen.next(),
                no=sl_no.next(move.ts.date()),
                bill_type=move.bill_type,
                work_bill_id=move.work_bill_id,
                business_source_id=move.source_bill_id,
                business_source_no=move.source_no,
                entry_mode=move.entry_mode,
                warehouse_index=move.warehouse_index,
                confirmed_at=move.ts,
            )
            by_work[move.work_bill_id] = bill
            bills.append(bill)
        else:
            bill = by_work[move.work_bill_id]
        item_id = item_id_gen.next()
        bill.lines.append({
            "id": item_id,
            "work_bill_item_id": move.work_item_id,
            "business_source_item_id": move.source_item_id,
            "product": move.product,
            "qualified_qty": move.qualified_qty,
            "defective_qty": move.defective_qty,
            "before_qty": before,
            "change_qty": move.change,
            "after_qty": after,
        })
        move.stock_bill_item_id = item_id
    return bills, running


def calc_locked(result: SimulationResult) -> dict[tuple[int, int], int]:
    """计算期末锁定量：未完成销售单 + 未执行采购退货。"""
    locked: dict[tuple[int, int], int] = {}
    for order in result.sales_orders:
        if order.status in ("SUBMITTED", "APPROVED"):
            for line in order.lines:
                key = (order.warehouse_index, line.product.id)
                locked[key] = locked.get(key, 0) + line.quantity
        elif order.status == "PARTIAL_OUTBOUND":
            for line in order.lines:
                key = (order.warehouse_index, line.product.id)
                locked[key] = locked.get(key, 0) + max(0, line.quantity - line.outbound_qty)
    for ret in result.return_orders:
        if ret.return_type == "PURCHASE_RETURN" and ret.status in ("APPROVED", "PARTIAL_EXECUTED"):
            line = ret.lines[0]
            key = (ret.warehouse_index, line["product"].id)
            locked[key] = locked.get(key, 0) + max(0, line["approved_qty"] - line["processed_qty"])
    return locked


def penalty_rate(expected: date, actual: date) -> int:
    days = (actual - expected).days
    if days <= 0:
        return 0
    if days <= 3:
        return 25
    if days <= 7:
        return 50
    if days <= 15:
        return 75
    return 100


def sync_pending_outbound(order: SalesOrder) -> None:
    """销售单数量缩减后同步待确认出库单，保持 plan/pending 与销售明细一致。"""
    line_by_id = {line.item_id: line for line in order.lines}
    for bill in order.outbound_bills:
        if bill.status != "PENDING_CONFIRM":
            continue
        kept = []
        for item in bill.lines:
            sales_line = line_by_id.get(item["source_item_id"])
            if sales_line is None:
                continue
            item["quantity"] = sales_line.quantity
            item["plan_qty"] = sales_line.quantity
            item["pending_qty"] = sales_line.quantity - item.get("processed_qty", 0)
            item["current_qty"] = 0
            kept.append(item)
        bill.lines = kept


def rebalance_locked(result: SimulationResult,
                     final_stock: dict[tuple[int, int], int]) -> dict[tuple[int, int], int]:
    """库存不足以支撑锁定量时，按占用顺序缩减未完成单据，保证 locked <= stock。"""
    for _ in range(12):
        locked = calc_locked(result)
        changed = False
        for (wi, pid), locked_qty in list(locked.items()):
            stock_qty = final_stock.get((wi, pid), 0)
            if locked_qty <= stock_qty:
                continue
            excess = locked_qty - stock_qty
            # 先缩减采购退货的未执行量。
            for ret in result.return_orders:
                if excess <= 0:
                    break
                if ret.return_type != "PURCHASE_RETURN" or ret.warehouse_index != wi:
                    continue
                if ret.status not in ("APPROVED", "PARTIAL_EXECUTED"):
                    continue
                line = ret.lines[0]
                if line["product"].id != pid:
                    continue
                available = line["approved_qty"] - line["processed_qty"]
                cut = min(excess, available)
                if cut <= 0:
                    continue
                line["approved_qty"] -= cut
                line["total_amount"] = line["requested_qty"] * line["unit_price"]
                ret.total_amount = line["total_amount"]
                excess -= cut
                changed = True
                if line["approved_qty"] <= line["processed_qty"]:
                    ret.status = "CANCELLED"
                    if ret.execution_bill is not None:
                        ret.execution_bill.status = "CANCELLED"
                        ret.execution_bill.lines = []
                elif ret.execution_bill is not None and ret.execution_bill.status == "PENDING_CONFIRM":
                    item = ret.execution_bill.lines[0]
                    item["quantity"] = line["approved_qty"]
                    item["plan_qty"] = line["approved_qty"]
                    item["pending_qty"] = line["approved_qty"] - line["processed_qty"]
            # 再缩减未完成销售单。
            for order in result.sales_orders:
                if excess <= 0:
                    break
                if order.warehouse_index != wi:
                    continue
                if order.status not in ("SUBMITTED", "APPROVED", "PARTIAL_OUTBOUND"):
                    continue
                for line in order.lines:
                    if excess <= 0:
                        break
                    if line.product.id != pid:
                        continue
                    if order.status == "PARTIAL_OUTBOUND":
                        max_cut = max(0, line.quantity - line.outbound_qty)
                    else:
                        max_cut = line.quantity
                    cut = min(excess, max_cut)
                    if cut <= 0:
                        continue
                    line.quantity -= cut
                    line.total_amount = line.quantity * line.unit_price
                    line.locked_qty = line.quantity if order.status != "PARTIAL_OUTBOUND" else line.locked_qty
                    excess -= cut
                    changed = True
                    if line.quantity <= 0:
                        order.lines.remove(line)
                        if not order.lines:
                            order.status = "DRAFT"
                            order.submitted_at = None
                            order.approved_at = None
                            order.locked_at = None
                            for bill in order.outbound_bills:
                                if bill.status == "PENDING_CONFIRM":
                                    bill.status = "CANCELLED"
                                    bill.lines = []
                            break
                    if order.status == "PARTIAL_OUTBOUND":
                        # 剩余未出库量减少后，锁定量随之下降；剩余归零则完成订单。
                        if line.quantity <= line.outbound_qty:
                            line.quantity = line.outbound_qty
                        line.locked_qty = max(0, line.quantity - line.outbound_qty)
                        if all(item.outbound_qty >= item.quantity for item in order.lines):
                            order.status = "OUTBOUND_DONE"
                            for pending in order.outbound_bills:
                                if pending.status == "PENDING_CONFIRM":
                                    pending.status = "CANCELLED"
                                    pending.lines = []
                    sync_pending_outbound(order)
                    if not order.lines:
                        break
        if not changed:
            break
    # 收尾：剩余量已归零的部分出库单推进为完成状态，并取消多余的待确认出库单。
    for order in result.sales_orders:
        if order.status == "PARTIAL_OUTBOUND" and order.lines and all(
                line.outbound_qty >= line.quantity for line in order.lines):
            order.status = "OUTBOUND_DONE"
            for bill in order.outbound_bills:
                if bill.status == "PENDING_CONFIRM":
                    bill.status = "CANCELLED"
                    bill.lines = []
    return calc_locked(result)


def calc_scores(result: SimulationResult, relations: dict[int, list[SupplyRelation]],
                suppliers: list[Supplier], products: list[Product]) -> None:
    """按采购事实计算供应商与供货关系评分。"""
    window_start = TODAY - timedelta(days=180)
    relation_by_product: dict[int, list[SupplyRelation]] = relations
    relation_by_id: dict[int, SupplyRelation] = {}
    for items in relations.values():
        for relation in items:
            relation_by_id[relation.id] = relation
    purchase_line_by_id: dict[int, PurchaseLine] = {}
    for order in result.purchase_orders:
        for line in order.lines:
            purchase_line_by_id[line.item_id] = line

    supplier_good: dict[int, int] = {}
    supplier_bad: dict[int, int] = {}
    supplier_due: dict[int, int] = {}
    supplier_penalty: dict[int, float] = {}
    supplier_receive_amount: dict[int, int] = {}
    supplier_receive_days: dict[int, int] = {}

    # 1. 入库事实：质量金额、累计基准金额、最新成交价。
    for move in result.moves:
        if move.bill_type != "PURCHASE_IN" or move.source_item_id is None:
            continue
        line = purchase_line_by_id.get(move.source_item_id)
        if line is None:
            continue
        relation = line.relation
        amount = move.change * line.unit_price // SCALE
        relation.score_basis_amount += amount
        if move.ts.date() >= window_start:
            good_amount = move.qualified_qty * line.unit_price // SCALE
            bad_amount = move.defective_qty * line.unit_price // SCALE
            relation.quality_amount_good += good_amount
            relation.quality_amount_bad += bad_amount
            supplier_good[relation.supplier_id] = supplier_good.get(relation.supplier_id, 0) + good_amount
            supplier_bad[relation.supplier_id] = supplier_bad.get(relation.supplier_id, 0) + bad_amount
        if relation.last_purchase_at is None or move.ts > relation.last_purchase_at:
            relation.last_purchase_at = move.ts
            relation.latest_purchase_price = line.unit_price

    # 2. 交付事实：近 180 天已到期采购单的应交金额与逾期罚额。
    for order in result.purchase_orders:
        if order.status not in ("APPROVED", "PARTIAL_INBOUND", "INBOUND_DONE"):
            continue
        if not (window_start <= order.expected_arrival < TODAY):
            continue
        due = 0
        penalty = 0.0
        valid = True
        for line in order.lines:
            due += line.total_amount
            received = 0
            weighted = 0
            for qty, day, _qualified, _defective in line.batches:
                received += qty
                weighted += qty * penalty_rate(order.expected_arrival, day)
            weighted += max(0, line.quantity - received) * penalty_rate(order.expected_arrival, TODAY)
            if line.quantity <= 0:
                valid = False
                break
            penalty += line.total_amount * weighted / (line.quantity * SCALE)
        if not valid:
            continue
        supplier_due[order.supplier.id] = supplier_due.get(order.supplier.id, 0) + due
        supplier_penalty[order.supplier.id] = supplier_penalty.get(order.supplier.id, 0.0) + penalty
        if order.status == "INBOUND_DONE" and order.fully_received_at and order.approved_at:
            days = (order.fully_received_at - order.approved_at).days
            if days >= 0:
                amount = sum(line.total_amount for line in order.lines)
                supplier_receive_amount[order.supplier.id] = supplier_receive_amount.get(order.supplier.id, 0) + amount
                supplier_receive_days[order.supplier.id] = supplier_receive_days.get(order.supplier.id, 0) + amount * days

    # 3. 供货关系价格分与质量分。
    for relation in relation_by_id.values():
        product = relation.product
        if (relation.status == 1 and relation.quote_cents > 0 and product.price_cents > 0
                and relation.quote_valid_until >= TODAY):
            raw = product.price_cents * 10000 / relation.quote_cents
            relation.price_score = min(10000, round_half_up(raw))
        else:
            relation.price_score = None
        total = relation.quality_amount_good + relation.quality_amount_bad
        relation.quality_score = (round_half_up(relation.quality_amount_good * 10000 / total)
                                  if total > 0 else None)

    # 4. 供应商维度评分。
    for supplier in suppliers:
        due = supplier_due.get(supplier.id, 0)
        if due > 0:
            penalty = supplier_penalty.get(supplier.id, 0.0)
            value = round_half_up(10000 * (1 - penalty / due))
            supplier_delivery_score = max(0, min(10000, value))
        else:
            supplier_delivery_score = None
        good = supplier_good.get(supplier.id, 0)
        bad = supplier_bad.get(supplier.id, 0)
        supplier_quality_score = round_half_up(good * 10000 / (good + bad)) if (good + bad) > 0 else None
        # 供应商价格分按各供货关系入库金额加权。
        pairs = [(r.score_basis_amount, r.price_score) for r in relation_by_id.values()
                 if r.supplier_id == supplier.id and r.price_score is not None and r.score_basis_amount > 0]
        if pairs:
            total_amount = sum(amount for amount, _ in pairs)
            supplier_price_score = round_half_up(sum(amount * score for amount, score in pairs) / total_amount)
        else:
            supplier_price_score = None
        supplier.delivery_score = supplier_delivery_score
        supplier.quality_score = supplier_quality_score
        supplier.price_score = supplier_price_score
        if supplier_receive_amount.get(supplier.id, 0) > 0:
            supplier.avg_delivery_days = round(supplier_receive_days[supplier.id] / supplier_receive_amount[supplier.id], 2)
        else:
            supplier.avg_delivery_days = None
        supplier.score_basis_amount = sum(r.score_basis_amount for r in relation_by_id.values()
                                          if r.supplier_id == supplier.id)
        # 综合分：任一维度缺失即不可用。
        if None in (supplier_price_score, supplier_delivery_score, supplier_quality_score, supplier.service_score):
            supplier.overall_score = None
            supplier.score_status = "NOT_READY"
        else:
            supplier.overall_score = round_half_up(
                supplier_price_score * 0.3 + supplier_delivery_score * 0.3
                + supplier_quality_score * 0.3 + supplier.service_score * 0.1)
            supplier.overall_score = max(0, min(10000, supplier.overall_score))
            supplier.score_status = "READY"

    # 5. 供货关系推荐分。
    for relation in relation_by_id.values():
        supplier = relation.supplier
        if None in (relation.price_score, supplier.delivery_score, relation.quality_score, supplier.service_score):
            relation.recommend_score = None
            relation.score_status = "NOT_READY"
        else:
            relation.recommend_score = round_half_up(
                relation.price_score * 0.3 + supplier.delivery_score * 0.3
                + relation.quality_score * 0.3 + supplier.service_score * 0.1)
            relation.recommend_score = max(0, min(10000, relation.recommend_score))
            relation.score_status = "READY"


def latest_time(*values: datetime | None) -> datetime:
    """取最后一个非空时间作为更新时间。"""
    times = [value for value in values if value is not None]
    return max(times) if times else datetime(2026, 11, 21, 18, 0, 0)


def dump_transactions(result: SimulationResult, stock_bills: list[StockBill],
                      final_stock: dict[tuple[int, int], int],
                      locked: dict[tuple[int, int], int],
                      warehouses: list[Warehouse]) -> list[str]:
    """输出交易单据、库存流水与库存余额的 INSERT 语句。"""
    chunks: list[str] = []

    def emit(table: str, columns: list[str], rows: list[list[object]], chunk_size: int = 500) -> None:
        if not rows:
            return
        header = f"INSERT INTO `{table}` ({', '.join('`' + c + '`' for c in columns)}) VALUES\n"
        for start in range(0, len(rows), chunk_size):
            part = rows[start:start + chunk_size]
            values = ",\n".join("(" + ", ".join(sql_quote(v) for v in row) + ")" for row in part)
            chunks.append(header + values + ";\n")

    actor = Raw("@demo_actor")
    actor_name = Raw("@demo_actor_name")

    def norm_times(created: datetime, submitted: datetime | None,
                   approved: datetime | None) -> tuple[datetime, datetime | None, datetime | None]:
        """保证提交、审核时间不早于创建时间，避免单据时间线倒挂。"""
        if submitted is not None and submitted < created:
            submitted = created + timedelta(minutes=20)
        if approved is not None:
            base = submitted if submitted is not None else created
            if approved < base:
                approved = base + timedelta(minutes=20)
        return created, submitted, approved
    item_stock_bill = {move.work_item_id: move.stock_bill_item_id
                       for move in result.moves if move.stock_bill_item_id}
    # 每张出入库单的仓库取自其库存变动记录，兼容调整单和调拨单。
    bill_warehouse = {move.work_bill_id: move.warehouse_index for move in result.moves}

    def resolve_warehouse_index(bill) -> int:
        index = bill_warehouse.get(bill.id)
        if index is not None:
            return index
        if bill.order is not None:
            return bill.order.warehouse_index
        if bill.return_order is not None:
            return bill.return_order.warehouse_index
        raise ValueError(f"无法确定出入库单仓库：{bill.no}")

    # 采购订单
    po_rows = []
    for order in result.purchase_orders:
        created_ts, submitted_ts, approved_ts = norm_times(
            order.created_at, order.submitted_at, order.approved_at)
        po_rows.append([
            order.id, order.no, order.supplier.id, order.supplier.code, order.supplier.name,
            warehouses[order.warehouse_index].id, warehouses[order.warehouse_index].name,
            order.status, sum(line.total_amount for line in order.lines),
            order.expected_arrival, actor, actor_name,
            submitted_ts, actor if submitted_ts else None, actor_name if submitted_ts else "",
            approved_ts, actor if approved_ts else None, actor_name if approved_ts else "",
            order.fully_received_at,
            order.cancelled_at, order.cancel_reason, order.cancel_affects_score,
            actor if order.cancelled_at else None, actor_name if order.cancelled_at else "",
            order.created_at,
            latest_time(order.created_at, submitted_ts, approved_ts,
                        order.fully_received_at, order.cancelled_at),
            "", 0,
        ])
    emit("purchase_order",
         ["id", "purchase_no", "supplier_id", "supplier_code", "supplier_name", "warehouse_id",
          "warehouse_name", "status", "total_amount", "expected_arrival_date", "created_by_id",
          "created_by_name", "submitted_at", "submitted_by_id", "submitted_by_name", "approved_at",
          "approved_by_id", "approved_by_name", "fully_received_at", "cancelled_at", "cancel_reason",
          "cancel_affects_delivery_score", "cancelled_by_id", "cancelled_by_name", "create_time",
          "update_time", "remark", "version"],
         po_rows)

    po_item_rows = []
    for order in result.purchase_orders:
        for line in order.lines:
            score = None if order.status in ("DRAFT", "SUBMITTED") else line.relation.recommend_score
            po_item_rows.append([
                line.item_id, order.id, order.no, line.relation.id,
                line.relation.product.id, line.relation.product.code, line.relation.product.name,
                line.relation.product.unit, 0,
                qty_to_stored(line.quantity), qty_to_stored(line.inbound_qty),
                line.unit_price, line.total_amount, score,
                order.created_at, latest_time(order.created_at, order.approved_at,
                                              order.fully_received_at, order.cancelled_at),
                "", 0,
            ])
    emit("purchase_order_item",
         ["id", "purchase_order_id", "purchase_no", "supplier_product_id", "product_id",
          "product_code", "product_name", "unit_name", "quantity_precision", "quantity",
          "inbound_qty", "unit_price", "total_amount", "selected_supplier_score", "create_time",
          "update_time", "remark", "version"],
         po_item_rows)

    # 入库单
    ib_rows = []
    ib_item_rows = []
    for bill in result.inbound_bills:
        order = bill.order
        ret = bill.return_order
        if bill.bill_type == "PURCHASE_IN" and order is not None:
            source_type = "PURCHASE_ORDER"
            source_id = order.id
            source_no = order.no
            party_id = order.supplier.id
            party_name = order.supplier.name
            expected = order.expected_arrival
        elif bill.bill_type == "SALES_RETURN" and ret is not None:
            source_type = "SALES_RETURN_ORDER"
            source_id = ret.id
            source_no = ret.no
            party_id = ret.party_id
            party_name = ret.party_name
            expected = None
        else:
            source_type = "STOCK_ADJUST"
            source_id = None
            source_no = ""
            party_id = None
            party_name = ""
            expected = None
        warehouse = warehouses[resolve_warehouse_index(bill)]
        update_time = bill.confirmed_at or bill.created_at
        ib_rows.append([
            bill.id, bill.no, bill.bill_type, source_type, source_id, source_no,
            party_id, party_name, bill.entry_mode,
            warehouse.id, warehouse.name, bill.status, expected,
            actor if bill.confirmed_at else None, actor_name if bill.confirmed_at else "",
            bill.confirmed_at, actor, actor_name, actor if bill.confirmed_at else None,
            actor_name if bill.confirmed_at else None,
            bill.created_at, update_time, "", "", 0,
        ])
        for item in bill.lines:
            product = item["product"]
            current = item["current_qty"]
            plan = item.get("plan_qty", current)
            processed = item.get("processed_qty", 0)
            pending = item.get("pending_qty", max(0, plan - processed - current))
            ib_item_rows.append([
                item["id"], bill.id, bill.no, item["source_item_id"],
                product.id, product.code, product.name, product.unit, 0,
                qty_to_stored(plan), qty_to_stored(processed), qty_to_stored(current),
                qty_to_stored(pending), qty_to_stored(item.get("qualified_qty", 0)),
                qty_to_stored(item.get("defective_qty", 0)), item["unit_price"],
                item_stock_bill.get(item["id"]),
                bill.created_at, update_time, "", 0,
            ])
    emit("inbound_bill",
         ["id", "inbound_no", "inbound_type", "source_type", "source_id", "source_no",
          "source_party_id", "source_party_name", "entry_mode", "warehouse_id", "warehouse_name",
          "status", "expected_arrival_date", "confirmed_by_id", "confirmed_by_name", "confirmed_at",
          "created_by_id", "created_by_name", "responsible_by_id", "responsible_by_name",
          "create_time", "update_time", "manual_reason", "remark", "version"],
         ib_rows)
    emit("inbound_bill_item",
         ["id", "inbound_bill_id", "inbound_no", "source_item_id", "product_id", "product_code",
          "product_name", "unit_name", "quantity_precision", "plan_qty", "processed_qty",
          "current_qty", "pending_qty", "qualified_qty", "defective_qty", "unit_price",
          "stock_bill_item_id", "create_time", "update_time", "remark", "version"],
         ib_item_rows)

    # 销售订单
    so_rows = []
    for order in result.sales_orders:
        created_ts, submitted_ts, approved_ts = norm_times(
            order.created_at, order.submitted_at, order.approved_at)
        so_rows.append([
            order.id, order.no, order.customer.id, order.customer.code, order.customer.name,
            warehouses[order.warehouse_index].id, warehouses[order.warehouse_index].name,
            order.status, sum(line.total_amount for line in order.lines), order.expected_delivery,
            order.locked_at, actor, actor_name,
            submitted_ts, actor if submitted_ts else None, actor_name if submitted_ts else "",
            approved_ts, actor if approved_ts else None, actor_name if approved_ts else "",
            order.created_at,
            latest_time(order.created_at, submitted_ts, approved_ts,
                        order.locked_at),
            "", 0,
        ])
    emit("sales_order",
         ["id", "sales_no", "customer_id", "customer_code", "customer_name", "warehouse_id",
          "warehouse_name", "status", "total_amount", "expected_delivery_date", "locked_at",
          "created_by_id", "created_by_name", "submitted_at", "submitted_by_id",
          "submitted_by_name", "approved_at", "approved_by_id", "approved_by_name",
          "create_time", "update_time", "remark", "version"],
         so_rows)

    so_item_rows = []
    for order in result.sales_orders:
        for line in order.lines:
            so_item_rows.append([
                line.item_id, order.id, order.no, line.product.id, line.product.code,
                line.product.name, line.product.unit, 0,
                qty_to_stored(line.quantity), qty_to_stored(line.locked_qty),
                qty_to_stored(line.outbound_qty), line.unit_price, line.total_amount,
                order.created_at, latest_time(order.created_at, order.approved_at, order.locked_at),
                "", 0,
            ])
    emit("sales_order_item",
         ["id", "sales_order_id", "sales_no", "product_id", "product_code", "product_name",
          "unit_name", "quantity_precision", "quantity", "locked_qty", "outbound_qty",
          "unit_price", "total_amount", "create_time", "update_time", "remark", "version"],
         so_item_rows)

    # 出库单
    ob_rows = []
    ob_item_rows = []
    for bill in result.outbound_bills:
        order = bill.order
        ret = bill.return_order
        if bill.bill_type == "SALES_OUT" and order is not None:
            source_type = "SALES_ORDER"
            source_id = order.id
            source_no = order.no
            party_id = order.customer.id
            party_name = order.customer.name
        elif bill.bill_type == "PURCHASE_RETURN" and ret is not None:
            source_type = "PURCHASE_RETURN_ORDER"
            source_id = ret.id
            source_no = ret.no
            party_id = ret.party_id
            party_name = ret.party_name
        else:
            source_type = "STOCK_ADJUST"
            source_id = None
            source_no = ""
            party_id = None
            party_name = ""
        warehouse = warehouses[resolve_warehouse_index(bill)]
        update_time = bill.confirmed_at or bill.created_at
        ob_rows.append([
            bill.id, bill.no, bill.bill_type, source_type, source_id, source_no,
            party_id, party_name, bill.entry_mode,
            warehouse.id, warehouse.name, bill.status,
            actor if bill.confirmed_at else None, actor_name if bill.confirmed_at else "",
            bill.confirmed_at, actor, actor_name, actor if bill.confirmed_at else None,
            actor_name if bill.confirmed_at else None,
            bill.created_at, update_time, "", "", 0,
        ])
        for item in bill.lines:
            product = item["product"]
            current = item["current_qty"]
            plan = item.get("plan_qty", item.get("quantity", current))
            processed = item.get("processed_qty", 0)
            pending = item.get("pending_qty", max(0, plan - processed - current))
            ob_item_rows.append([
                item["id"], bill.id, bill.no, item["source_item_id"],
                product.id, product.code, product.name, product.unit, 0,
                qty_to_stored(plan), qty_to_stored(processed), qty_to_stored(current),
                qty_to_stored(pending), 0, 0,
                item_stock_bill.get(item["id"]),
                bill.created_at, update_time, "", 0,
            ])
    emit("outbound_bill",
         ["id", "outbound_no", "outbound_type", "source_type", "source_id", "source_no",
          "source_party_id", "source_party_name", "entry_mode", "warehouse_id", "warehouse_name",
          "status", "confirmed_by_id", "confirmed_by_name", "confirmed_at", "created_by_id",
          "created_by_name", "responsible_by_id", "responsible_by_name", "create_time",
          "update_time", "manual_reason", "remark", "version"],
         ob_rows)
    emit("outbound_bill_item",
         ["id", "outbound_bill_id", "outbound_no", "source_item_id", "product_id", "product_code",
          "product_name", "unit_name", "quantity_precision", "plan_qty", "processed_qty",
          "current_qty", "pending_qty", "qualified_qty", "defective_qty",
          "stock_bill_item_id", "create_time", "update_time", "remark", "version"],
         ob_item_rows)

    # 退货单
    ro_rows = []
    roi_rows = []
    for ret in result.return_orders:
        created_ts, submitted_ts, approved_ts = norm_times(
            ret.created_at, ret.submitted_at, ret.approved_at)
        ro_rows.append([
            ret.id, ret.no, ret.return_type, ret.source_order_id, ret.source_order_no,
            ret.party_id, ret.party_code, ret.party_name,
            warehouses[ret.warehouse_index].id, warehouses[ret.warehouse_index].name,
            ret.expected_execution_date, ret.handling_type, ret.reason_code, ret.return_reason,
            ret.total_amount, ret.status, "",
            actor, actor_name, submitted_ts,
            actor if approved_ts else None, actor_name if approved_ts else "",
            approved_ts,
            ret.created_at, latest_time(ret.created_at, submitted_ts, approved_ts),
            "", 0,
        ])
        for line in ret.lines:
            roi_rows.append([
                line["id"], ret.id, line["source_item_id"],
                line["product"].id, line["product"].code, line["product"].name,
                line["product"].unit, 0,
                qty_to_stored(line["source_fulfilled_qty"]), qty_to_stored(line["requested_qty"]),
                qty_to_stored(line["approved_qty"]), qty_to_stored(line["processed_qty"]),
                line["unit_price"], line["total_amount"],
                ret.created_at, latest_time(ret.created_at, ret.submitted_at, ret.approved_at),
                "",
            ])
    emit("return_order",
         ["id", "return_no", "return_type", "source_order_id", "source_order_no", "party_id",
          "party_code", "party_name", "warehouse_id", "warehouse_name",
          "expected_execution_date", "handling_type", "reason_code", "return_reason",
          "total_amount", "status", "status_reason", "created_by_id", "created_by_name",
          "submitted_at", "approved_by_id", "approved_by_name", "approved_at", "create_time",
          "update_time", "remark", "version"],
         ro_rows)
    emit("return_order_item",
         ["id", "return_order_id", "source_order_item_id", "product_id", "product_code",
          "product_name", "unit_name", "quantity_precision", "source_fulfilled_qty",
          "requested_qty", "approved_qty", "processed_qty", "unit_price", "total_amount",
          "create_time", "update_time", "remark"],
         roi_rows)

    # 库存流水
    sb_rows = []
    sbi_rows = []
    for bill in stock_bills:
        warehouse = warehouses[bill.warehouse_index]
        sb_rows.append([
            bill.id, bill.no, bill.bill_type, bill.work_bill_id,
            bill.business_source_id, bill.business_source_no, bill.entry_mode,
            warehouse.id, warehouse.name, actor, actor_name, bill.confirmed_at,
            bill.confirmed_at, "",
        ])
        for line in bill.lines:
            product = line["product"]
            sbi_rows.append([
                line["id"], bill.id, line["work_bill_item_id"], line["business_source_item_id"],
                product.id, product.code, product.name, product.unit, 0,
                qty_to_stored(line["qualified_qty"]), qty_to_stored(line["defective_qty"]),
                qty_to_stored(line["before_qty"]), qty_to_stored(line["change_qty"]),
                qty_to_stored(line["after_qty"]), bill.confirmed_at, "",
            ])
    emit("stock_bill",
         ["id", "bill_no", "bill_type", "work_bill_id", "business_source_id",
          "business_source_no", "entry_mode", "warehouse_id", "warehouse_name",
          "confirmed_by_id", "confirmed_by_name", "confirmed_at", "create_time", "remark"],
         sb_rows)
    emit("stock_bill_item",
         ["id", "bill_id", "work_bill_item_id", "business_source_item_id", "product_id",
          "product_code", "product_name", "unit_name", "quantity_precision", "qualified_qty",
          "defective_qty", "before_qty", "change_qty", "after_qty", "create_time", "remark"],
         sbi_rows)

    # 库存余额：只输出有库存或锁定的仓库商品。
    product_by_id = {move.product.id: move.product for move in result.moves}
    ws_rows = []
    next_id = ID_WAREHOUSE_STOCK
    keys = sorted(set(final_stock) | set(locked))
    for wi, pid in keys:
        stock_qty = final_stock.get((wi, pid), 0)
        locked_qty = locked.get((wi, pid), 0)
        if stock_qty == 0 and locked_qty == 0:
            continue
        if locked_qty > stock_qty:
            raise ValueError(f"锁定量超过库存：仓库 {wi} 商品 {pid} stock={stock_qty} locked={locked_qty}")
        product = product_by_id.get(pid)
        if product is None:
            continue
        warehouse = warehouses[wi]
        ws_rows.append([next_id, warehouse.id, warehouse.code, warehouse.name, product.id,
                        product.code, product.name, product.unit,
                        qty_to_stored(stock_qty), qty_to_stored(locked_qty),
                        "2025-10-31 18:00:00", "2026-11-21 23:00:00", 0])
        next_id += 1
    emit("warehouse_stock",
         ["id", "warehouse_id", "warehouse_code", "warehouse_name", "product_id", "product_code",
          "product_name", "unit_name", "stock_qty", "locked_qty", "create_time", "update_time",
          "version"],
         ws_rows)

    return chunks


def main() -> int:
    categories = load_categories()
    seeds = load_products()
    warehouses = build_warehouses()
    suppliers = build_suppliers(categories)
    customers = build_customers(warehouses)
    products = build_products(seeds, categories)
    relations = build_supply_relations(products, suppliers)

    result = simulate(products, relations, warehouses, customers)
    calc_scores(result, relations, suppliers, products)
    stock_bills, final_stock = build_stock_bills(result.moves, warehouses)
    locked = rebalance_locked(result, final_stock)

    header = (
        "-- ERP 演示数据 v2（由 generate_demo_data.py 生成，固定随机种子，可重复生成）\n"
        "-- 数据全部为虚构演示数据，覆盖近 12 个月采购、销售、库存、退货链路。\n"
        "-- 适用：已完成 01-schema.sql 与 02-system-demo.sql 初始化的空库。\n"
        "SET NAMES utf8mb4;\n"
        "SET @demo_actor = (SELECT id FROM sys_user WHERE deleted=0 AND status=1 ORDER BY is_admin DESC,id LIMIT 1);\n"
        "SET @demo_actor_name = (SELECT real_name FROM sys_user WHERE id=@demo_actor);\n"
        "START TRANSACTION;\n"
    )
    chunks = [header]
    chunks += dump_master_data(warehouses, suppliers, customers, products, categories, relations)
    chunks += dump_transactions(result, stock_bills, final_stock, locked, warehouses)
    chunks.append("COMMIT;\n")

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    (OUT_DIR / "demo-data-v2.sql").write_text("".join(chunks), encoding="utf-8")

    # 同步写一份主数据，便于单独检查。
    (OUT_DIR / "master-data-v2.sql").write_text(
        "".join(dump_master_data(warehouses, suppliers, customers, products, categories, relations)),
        encoding="utf-8")

    write_stats(result, stock_bills, final_stock, locked, relations, suppliers, products, warehouses)

    # 同步检查脚本模板到输出目录。
    verify_template = BASE_DIR / "verify-data-v2.sql"
    if verify_template.exists():
        (OUT_DIR / "verify-data-v2.sql").write_text(
            verify_template.read_text(encoding="utf-8"), encoding="utf-8")
    return 0


def write_stats(result: SimulationResult, stock_bills: list[StockBill],
                final_stock: dict[tuple[int, int], int], locked: dict[tuple[int, int], int],
                relations: dict[int, list[SupplyRelation]], suppliers: list[Supplier],
                products: list[Product], warehouses: list[Warehouse]) -> None:
    """输出分布统计，便于人工核对。"""
    lines: list[str] = []

    def add(text: str = "") -> None:
        lines.append(text)

    add("=== 数据规模 ===")
    add(f"商品 {len(products)}，供应商 {len(suppliers)}，供货关系 {sum(len(v) for v in relations.values())}，"
        f"多源商品 {sum(1 for v in relations.values() if len(v) >= 2)}")
    add(f"采购单 {len(result.purchase_orders)}，采购明细 {sum(len(o.lines) for o in result.purchase_orders)}")
    add(f"销售单 {len(result.sales_orders)}，销售明细 {sum(len(o.lines) for o in result.sales_orders)}")
    add(f"入库单 {len(result.inbound_bills)}，出库单 {len(result.outbound_bills)}")
    add(f"退货单 {len(result.return_orders)}，库存流水凭证 {len(stock_bills)}，"
        f"流水明细 {sum(len(b.lines) for b in stock_bills)}")

    add()
    add("=== 采购单状态 ===")
    for status, count in sorted(_count(o.status for o in result.purchase_orders).items()):
        add(f"{status}: {count}")
    add()
    add("=== 销售单状态 ===")
    for status, count in sorted(_count(o.status for o in result.sales_orders).items()):
        add(f"{status}: {count}")
    add()
    add("=== 退货单状态 ===")
    for status, count in sorted(_count(o.status for o in result.return_orders).items()):
        add(f"{status}: {count}")
    add()
    add("=== 单据明细行数 ===")
    add(f"采购单多行占比：{_multi_line_ratio([len(o.lines) for o in result.purchase_orders])}")
    add(f"销售单多行占比：{_multi_line_ratio([len(o.lines) for o in result.sales_orders])}")
    add()
    add("=== 供应商评分 ===")
    deliveries = [s.delivery_score for s in suppliers if s.delivery_score is not None]
    qualities = [s.quality_score for s in suppliers if s.quality_score is not None]
    overalls = [s.overall_score for s in suppliers if s.overall_score is not None]
    add(f"READY {sum(1 for s in suppliers if s.score_status == 'READY')}，"
        f"NOT_READY {sum(1 for s in suppliers if s.score_status == 'NOT_READY')}")
    add(f"交付分范围 {min(deliveries) if deliveries else '-'} ~ {max(deliveries) if deliveries else '-'}，"
        f"质量分范围 {min(qualities) if qualities else '-'} ~ {max(qualities) if qualities else '-'}")
    add(f"综合分范围 {min(overalls) if overalls else '-'} ~ {max(overalls) if overalls else '-'}，"
        f"不同综合分 {len(set(overalls))}")
    add()
    add("=== 库存水位 ===")
    zero = low = high = normal = 0
    for product in products:
        qty = final_stock.get((product.warehouses[0], product.id), 0)
        if qty <= 0:
            zero += 1
        elif qty < product.safety_stock:
            low += 1
        elif qty > product.monthly_demand * 2:
            high += 1
        else:
            normal += 1
    add(f"零库存 {zero}，低于安全库存 {low}，积压(>2个月) {high}，正常 {normal}")
    add(f"锁定量合计 {sum(locked.values())}，跨仓商品 {sum(1 for p in products if len(p.warehouses) > 1)}")
    add()
    add("=== 价格与时间 ===")
    add(f"不同参考采购价 {len(set(p.price_cents for p in products))}")
    sale_ratios = [p.sale_price_cents / p.price_cents for p in products]
    add(f"售价/采购价比例 {min(sale_ratios):.2f} ~ {max(sale_ratios):.2f}")
    months: dict[str, int] = {}
    for order in result.purchase_orders:
        key = order.created_at.strftime("%Y-%m")
        months[key] = months.get(key, 0) + 1
    add("月度采购单量：" + "，".join(f"{k}:{v}" for k, v in sorted(months.items())))
    add()
    add("=== 仓库负载 ===")
    for index, warehouse in enumerate(warehouses):
        count = sum(1 for product in products if index in product.warehouses)
        qty = sum(final_stock.get((index, p.id), 0) for p in products)
        add(f"{warehouse.name}: 商品 {count}，期末库存 {qty}")

    (OUT_DIR / "stats-v2.txt").write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines))


def _count(values) -> dict[str, int]:
    result: dict[str, int] = {}
    for value in values:
        result[value] = result.get(value, 0) + 1
    return result


def _multi_line_ratio(line_counts: list[int]) -> str:
    if not line_counts:
        return "-"
    multi = sum(1 for count in line_counts if count > 1)
    return f"{multi}/{len(line_counts)} = {multi / len(line_counts):.1%}"


if __name__ == "__main__":
    sys.exit(main())