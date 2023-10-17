/*
 Navicat Premium Data Transfer

 Source Server         : L2j Servers DB
 Source Server Type    : MySQL
 Source Server Version : 50173
 Source Host           : localhost:3306
 Source Schema         : l2srls

 Target Server Type    : MySQL
 Target Server Version : 50173
 File Encoding         : 65001

 Date: 07/02/2021 13:30:11
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for account_data
-- ----------------------------
DROP TABLE IF EXISTS `account_data`;
CREATE TABLE `account_data`  (
  `account_name` varchar(45) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `var` varchar(20) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `value` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
  PRIMARY KEY (`account_name`, `var`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8 COLLATE = utf8_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Table structure for accounts
-- ----------------------------
DROP TABLE IF EXISTS `accounts`;
CREATE TABLE `accounts`  (
  `login` varchar(45) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `password` varchar(45) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
  `email` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
  `created_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `lastactive` bigint(13) UNSIGNED NOT NULL DEFAULT 0,
  `accessLevel` tinyint(4) NOT NULL DEFAULT 0,
  `lastIP` char(15) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
  `lastServer` tinyint(4) NULL DEFAULT 1,
  `pcIp` char(15) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
  `hop1` char(15) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
  `hop2` char(15) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
  `hop3` char(15) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
  `hop4` char(15) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
  PRIMARY KEY (`login`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8 COLLATE = utf8_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of accounts
-- ----------------------------
INSERT INTO `accounts` VALUES ('admin', '0DPiKuNIrrVmD8IUCuw1hQxNqZc=', NULL, '2018-09-06 14:42:27', 1612633397070, 0, '127.0.0.1', 1, '127.0.0.1', '0.0.0.0', '0.0.0.0', '0.0.0.0', '0.0.0.0');
INSERT INTO `accounts` VALUES ('kira2', 'etxkS2vqPAXI8CRDRSq4is1ZHhk=', NULL, '2018-09-08 18:45:39', 1536443144018, 0, '127.0.0.1', 1, '192.168.43.226', '0.0.0.0', '0.0.0.0', '0.0.0.0', '0.0.0.0');
INSERT INTO `accounts` VALUES ('kuko', 'iJTD5bHV2x8ENFa4dukcl49W/dw=', NULL, '2018-09-07 02:10:52', 1536297052312, 0, '127.0.0.1', 1, '192.168.43.226', '0.0.0.0', '0.0.0.0', '0.0.0.0', '0.0.0.0');

-- ----------------------------
-- Table structure for accounts_ipauth
-- ----------------------------
DROP TABLE IF EXISTS `accounts_ipauth`;
CREATE TABLE `accounts_ipauth`  (
  `login` varchar(45) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `ip` char(15) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `type` enum('deny','allow') CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT 'allow'
) ENGINE = InnoDB CHARACTER SET = utf8 COLLATE = utf8_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Table structure for gameservers
-- ----------------------------
DROP TABLE IF EXISTS `gameservers`;
CREATE TABLE `gameservers`  (
  `server_id` int(11) NOT NULL DEFAULT 0,
  `hexid` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  `host` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '',
  PRIMARY KEY (`server_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8 COLLATE = utf8_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of gameservers
-- ----------------------------
INSERT INTO `gameservers` VALUES (1, '28751147e5545d6b5feeaf0975e29f9b', '');

SET FOREIGN_KEY_CHECKS = 1;
