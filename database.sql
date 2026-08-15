CREATE DATABASE IF NOT EXISTS `ktor`;

USE `ktor`;

-- ============================================================
-- user
-- ============================================================
CREATE TABLE IF NOT EXISTS `user`
(
    `id`
    INT
    NOT
    NULL
    AUTO_INCREMENT,
    `userName`
    VARCHAR
(
    255
) NOT NULL,
    `email` VARCHAR
(
    255
) NOT NULL,
    `password` VARCHAR
(
    255
) NOT NULL,
    `isActive` TINYINT
(
    1
) NOT NULL DEFAULT 1,
    `avatar` VARCHAR
(
    255
) NOT NULL DEFAULT '',
    `createdAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updatedAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_user_userName` (`userName`),
    INDEX `idx_user_email` (`email`),
    INDEX `idx_user_password` (`password`)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- permission
-- ============================================================
CREATE TABLE IF NOT EXISTS `permission`
(
    `id`
    INT
    NOT
    NULL
    AUTO_INCREMENT,
    `name`
    VARCHAR
(
    100
) NOT NULL,
    `resourceType` ENUM
(
    'BOOK',
    'USER',
    'PERMISSION',
    'CHAPTER',
    'SHELF'
) NOT NULL,
    `action` ENUM
(
    'READ',
    'CREATE',
    'UPDATE',
    'DELETE',
    'AUDIT',
    'MANAGE'
) NOT NULL,
    `scope` ENUM
(
    'OWN',
    'ALL'
) NOT NULL,
    `bitPosition` INT NOT NULL,
    `createdAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updatedAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_permission_name` (`name`),
    UNIQUE KEY `uk_permission_bitPosition` (`bitPosition`),
    UNIQUE KEY `index_unique_code`
(
    `resourceType`,
    `action`,
    `scope`
)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- role
-- ============================================================
CREATE TABLE IF NOT EXISTS `role`
(
    `id`
    INT
    NOT
    NULL
    AUTO_INCREMENT,
    `name`
    VARCHAR
(
    50
) NOT NULL,
    `code` ENUM
(
    'USER',
    'REVIEWER',
    'AUTHOR',
    'ADMIN',
    'SUPER_ADMIN'
) NOT NULL,
    `description` VARCHAR
(
    255
) NULL DEFAULT NULL,
    `createdAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updatedAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_name` (`name`),
    UNIQUE KEY `uk_role_code` (`code`)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- book
-- ============================================================
CREATE TABLE IF NOT EXISTS `book`
(
    `id`
    INT
    NOT
    NULL
    AUTO_INCREMENT,
    `name`
    VARCHAR
(
    255
) NOT NULL,
    `author` VARCHAR
(
    255
) NOT NULL,
    `cover` VARCHAR
(
    255
) NOT NULL DEFAULT '',
    `description` TEXT NOT NULL,
    `category` VARCHAR
(
    255
) NOT NULL DEFAULT '',
    `tags` VARCHAR
(
    255
) NOT NULL DEFAULT '',
    `totalChapter` INT NOT NULL DEFAULT 0,
    `createdAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updatedAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `wordsCount` INT NOT NULL DEFAULT 0,
    `isActive` TINYINT
(
    1
) NOT NULL DEFAULT 1,
    `isEnded` TINYINT
(
    1
) NOT NULL DEFAULT 0,
    `status` ENUM
(
    'PENDING',
    'REVIEWING',
    'APPROVED',
    'REJECTED',
    'PUBLISHED'
) NOT NULL DEFAULT 'PENDING',
    PRIMARY KEY (`id`),
    INDEX `idx_book_name` (`name`),
    INDEX `idx_book_author` (`author`),
    INDEX `idx_book_category` (`category`),
    INDEX `idx_book_totalChapter` (`totalChapter`),
    INDEX `idx_book_wordsCount` (`wordsCount`),
    INDEX `idx_book_isEnded` (`isEnded`),
    INDEX `idx_category_isEnded` (`category`, `isEnded`),
    INDEX `idx_category_wordsCount`
(
    `category`,
    `wordsCount`
)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- author
-- ============================================================
CREATE TABLE IF NOT EXISTS `author`
(
    `userId`
    INT
    NOT
    NULL,
    `name`
    VARCHAR
(
    50
) NOT NULL,
    `createdAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updatedAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`userId`),
    UNIQUE KEY `uk_author_name` (`name`),
    CONSTRAINT `fk_author_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- book_chapter
-- ============================================================
CREATE TABLE IF NOT EXISTS `book_chapter`
(
    `id`
    INT
    NOT
    NULL
    AUTO_INCREMENT,
    `bookId`
    INT
    NOT
    NULL,
    `title`
    VARCHAR
(
    255
) NOT NULL DEFAULT '',
    `wordCount` INT NOT NULL,
    `createdAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updatedAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `status` ENUM
(
    'PENDING',
    'REVIEWING',
    'APPROVED',
    'REJECTED',
    'PUBLISHED'
) NOT NULL DEFAULT 'PENDING',
    `isActive` TINYINT
(
    1
) NOT NULL DEFAULT 1,
    `order` DECIMAL
(
    10,
    2
) NOT NULL DEFAULT 0.00,
    PRIMARY KEY (`id`),
    INDEX `idx_book_chapter_bookId` (`bookId`),
    UNIQUE INDEX `idx_book_chapter_bookId_order` (`bookId`, `order`),
    CONSTRAINT `fk_book_chapter_book` FOREIGN KEY (`bookId`) REFERENCES `book` (`id`)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- role_inheritance
-- ============================================================
CREATE TABLE IF NOT EXISTS `role_inheritance`
(
    `childId`
    INT
    NOT
    NULL,
    `parentId`
    INT
    NOT
    NULL,
    `createdAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`childId`, `parentId`),
    INDEX `fk_role_inheritance_parent` (`parentId`),
    CONSTRAINT `fk_role_inheritance_child` FOREIGN KEY (`childId`) REFERENCES `role` (`id`),
    CONSTRAINT `fk_role_inheritance_parent` FOREIGN KEY (`parentId`) REFERENCES `role` (`id`),
    CONSTRAINT `child_id_not_equal_parent_id` CHECK (`childId` <> `parentId`)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- role_permission
-- ============================================================
CREATE TABLE IF NOT EXISTS `role_permission`
(
    `roleId`
    INT
    NOT
    NULL,
    `permissionId`
    INT
    NOT
    NULL,
    `createdAt`
    DATETIME
    NOT
    NULL
    DEFAULT
    CURRENT_TIMESTAMP,
    PRIMARY KEY (`roleId`, `permissionId`),
    INDEX `fk_role_permission_permission` (`permissionId`),
    CONSTRAINT `fk_role_permission_role` FOREIGN KEY (`roleId`) REFERENCES `role` (`id`),
    CONSTRAINT `fk_role_permission_permission` FOREIGN KEY (`permissionId`) REFERENCES `permission` (`id`)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- user_role
-- ============================================================
CREATE TABLE IF NOT EXISTS `user_role`
(
    `userId`
    INT
    NOT
    NULL,
    `roleId`
    INT
    NOT
    NULL,
    `grantedBy`
    INT
    NULL
    DEFAULT
    NULL,
    `grantedAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`userId`, `roleId`),
    INDEX `fk_user_role_role` (`roleId`),
    INDEX `fk_user_role_granted_by` (`grantedBy`),
    CONSTRAINT `fk_user_role_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`),
    CONSTRAINT `fk_user_role_role` FOREIGN KEY (`roleId`) REFERENCES `role` (`id`),
    CONSTRAINT `fk_user_role_granted_by` FOREIGN KEY (`grantedBy`) REFERENCES `user` (`id`)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- author_book
-- ============================================================
CREATE TABLE IF NOT EXISTS `author_book`
(
    `userId`
    INT
    NOT
    NULL,
    `bookId`
    INT
    NOT
    NULL,
    `createdAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updatedAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`userId`, `bookId`),
    INDEX `fk_author_book_book` (`bookId`),
    CONSTRAINT `fk_author_book_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`),
    CONSTRAINT `fk_author_book_book` FOREIGN KEY (`bookId`) REFERENCES `book` (`id`)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- audit_book
-- ============================================================
CREATE TABLE IF NOT EXISTS `audit_book`
(
    `bookId`
    INT
    NOT
    NULL,
    `userId`
    INT
    NOT
    NULL,
    `createdAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updatedAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`bookId`, `userId`),
    INDEX `fk_audit_book_user` (`userId`),
    CONSTRAINT `fk_audit_book_book` FOREIGN KEY (`bookId`) REFERENCES `book` (`id`),
    CONSTRAINT `fk_audit_book_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- audit_book_chapter
-- ============================================================
CREATE TABLE IF NOT EXISTS `audit_book_chapter`
(
    `bookChapterId`
    INT
    NOT
    NULL,
    `userId`
    INT
    NOT
    NULL,
    `createdAt`
    DATETIME
    NOT
    NULL
    DEFAULT
    CURRENT_TIMESTAMP,
    `updatedAt`
    DATETIME
    NOT
    NULL
    DEFAULT
    CURRENT_TIMESTAMP
    ON
    UPDATE
    CURRENT_TIMESTAMP,
    PRIMARY KEY (`bookChapterId`, `userId`),
    INDEX `fk_audit_book_chapter_user` (`userId`),
    CONSTRAINT `fk_audit_book_chapter_chapter` FOREIGN KEY (`bookChapterId`) REFERENCES `book_chapter` (`id`),
    CONSTRAINT `fk_audit_book_chapter_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- shelf
-- ============================================================
CREATE TABLE IF NOT EXISTS `shelf`
(
    `id`
    INT
    NOT
    NULL
    AUTO_INCREMENT,
    `userId`
    INT
    NOT
    NULL,
    `bookId`
    INT
    NOT
    NULL,
    `createdAt`
    DATETIME
    NOT
    NULL
    DEFAULT
    CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `index_user_book_unique` (`userId`, `bookId`),
    INDEX `fk_shelf_book` (`bookId`),
    CONSTRAINT `fk_shelf_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`),
    CONSTRAINT `fk_shelf_book` FOREIGN KEY (`bookId`) REFERENCES `book` (`id`)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- chapter_read_statistics
-- ============================================================
CREATE TABLE IF NOT EXISTS `chapter_read_statistics`
(
    `id`
    INT
    NOT
    NULL
    AUTO_INCREMENT,
    `bookId`
    INT
    NOT
    NULL,
    `chapterId`
    INT
    NOT
    NULL,
    `hourStart`
    DATETIME
    NOT
    NULL,
    `uniqueReaderCount`
    INT
    NOT
    NULL
    DEFAULT
    0,
    `pageViewCount`
    INT
    NOT
    NULL
    DEFAULT
    0,
    `totalDuration`
    DECIMAL
(
    10,
    2
) NOT NULL DEFAULT 0.00,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uq_chapter_hour`
(
    `bookId`,
    `chapterId`,
    `hourStart`
),
    INDEX `ix_hour_start` (`hourStart`),
    INDEX `ix_book_hour` (`bookId`, `hourStart`),
    CONSTRAINT `fk_chapter_read_statistics_book` FOREIGN KEY (`bookId`) REFERENCES `book` (`id`),
    CONSTRAINT `fk_chapter_read_statistics_chapter` FOREIGN KEY (`chapterId`) REFERENCES `book_chapter` (`id`)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- user_read_event
-- ============================================================
CREATE TABLE IF NOT EXISTS `user_read_event`
(
    `id`
    INT
    NOT
    NULL
    AUTO_INCREMENT,
    `userId`
    INT
    NOT
    NULL,
    `bookId`
    INT
    NOT
    NULL,
    `chapterId`
    INT
    NOT
    NULL,
    `eventTime`
    DATETIME
    NOT
    NULL
    DEFAULT
    CURRENT_TIMESTAMP,
    `eventType`
    ENUM
(
    'ENTER',
    'EXIT',
    'HEARTBEAT'
) NOT NULL,
    PRIMARY KEY (`id`),
    INDEX `ix_event_time` (`eventTime`),
    INDEX `ix_user_book_chapter`
(
    `userId`,
    `bookId`,
    `chapterId`,
    `eventTime`
),
    INDEX `ix_book_chapter_time`
(
    `bookId`,
    `chapterId`,
    `eventTime`
),
    CONSTRAINT `fk_user_read_event_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`),
    CONSTRAINT `fk_user_read_event_book` FOREIGN KEY (`bookId`) REFERENCES `book` (`id`),
    CONSTRAINT `fk_user_read_event_chapter` FOREIGN KEY (`chapterId`) REFERENCES `book_chapter` (`id`)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- author_application
-- ============================================================
CREATE TABLE IF NOT EXISTS `author_application`
(
    `id`
    INT
    NOT
    NULL
    AUTO_INCREMENT,
    `userId`
    INT
    NOT
    NULL,
    `reason`
    VARCHAR
(
    500
) NOT NULL,
    `status` VARCHAR
(
    50
) NOT NULL DEFAULT 'pending',
    `handledBy` INT NULL DEFAULT NULL,
    `rejectReason` VARCHAR
(
    500
) NULL DEFAULT NULL,
    `handledAt` DATETIME NULL DEFAULT NULL,
    `createdAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updatedAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY
(
    `id`
),
    INDEX `idx_author_application_userId`
(
    `userId`
),
    INDEX `idx_author_application_status`
(
    `status`
),
    CONSTRAINT `fk_author_application_user` FOREIGN KEY
(
    `userId`
) REFERENCES `user`
(
    `id`
),
    CONSTRAINT `fk_author_application_handler` FOREIGN KEY
(
    `handledBy`
) REFERENCES `user`
(
    `id`
)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- user_reading_progress
-- ============================================================
CREATE TABLE IF NOT EXISTS `user_reading_progress`
(
    `id`
    INT
    NOT
    NULL
    AUTO_INCREMENT,
    `userId`
    INT
    NOT
    NULL,
    `bookId`
    INT
    NOT
    NULL,
    `lastChapterId`
    INT
    NOT
    NULL,
    `lastPosition`
    INT
    NOT
    NULL
    DEFAULT
    0,
    `lastReadAt`
    DATETIME
    NOT
    NULL
    DEFAULT
    CURRENT_TIMESTAMP
    ON
    UPDATE
    CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `index_user_book_unique` (`userId`, `bookId`),
    INDEX `fk_user_reading_progress_book` (`bookId`),
    INDEX `fk_user_reading_progress_chapter` (`lastChapterId`),
    CONSTRAINT `fk_user_reading_progress_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`),
    CONSTRAINT `fk_user_reading_progress_book` FOREIGN KEY (`bookId`) REFERENCES `book` (`id`),
    CONSTRAINT `fk_user_reading_progress_chapter` FOREIGN KEY (`lastChapterId`) REFERENCES `book_chapter` (`id`)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- book_comment
-- ============================================================
CREATE TABLE IF NOT EXISTS `book_comment`
(
    `id`
    INT
    NOT
    NULL
    AUTO_INCREMENT,
    `bookId`
    INT
    NOT
    NULL,
    `userId`
    INT
    NOT
    NULL,
    `status`
    ENUM
(
    'PENDING',
    'DELETING',
    'APPROVED',
    'REJECTED',
    'DELETED'
) NOT NULL DEFAULT 'APPROVED',
    `createdAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updatedAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `parentId` INT NULL,
    PRIMARY KEY
(
    `id`
),
    INDEX `idx_comment_status`
(
    `status`
),
    CONSTRAINT `fk_comment_book` FOREIGN KEY
(
    `bookId`
) REFERENCES `book`
(
    `id`
),
    CONSTRAINT `fk_comment_user` FOREIGN KEY
(
    `userId`
) REFERENCES `user`
(
    `id`
),
    CONSTRAINT `fk_comment_parent` FOREIGN KEY
(
    `parentId`
) REFERENCES `book_comment`
(
    `id`
)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `book_chapter_comment`
(
    `id`
    INT
    NOT
    NULL
    AUTO_INCREMENT,
    `chapterId`
    INT
    NOT
    NULL,
    `line`
    INT
    NOT
    NULL,
    `userId`
    INT
    NOT
    NULL,
    `status`
    ENUM
(
    'PENDING',
    'DELETING',
    'APPROVED',
    'REJECTED',
    'DELETED'
) NOT NULL DEFAULT 'APPROVED',
    `createdAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updatedAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `parentId` INT NULL,
    PRIMARY KEY
(
    `id`
),
    INDEX `idx_comment_status`
(
    `status`
),
    CONSTRAINT `fk_book_chapter_comment_book_chapter` FOREIGN KEY
(
    `chapterId`
) REFERENCES `book_chapter`
(
    `id`
),
    CONSTRAINT `fk_book_chapter_comment_user` FOREIGN KEY
(
    `userId`
) REFERENCES `user`
(
    `id`
),
    CONSTRAINT `fk_book_chapter_comment_parent` FOREIGN KEY
(
    `parentId`
) REFERENCES `book_chapter_comment`
(
    `id`
)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ============================================================
-- file_sync_outbox
-- 业务元数据与 outbox 记录在同一事务内写入，由后台 worker 消费执行文件操作，
-- 保证 DB 元数据与文件内容的最终一致。
-- ============================================================
use alpha;
CREATE TABLE IF NOT EXISTS `file_sync_outbox`
(
    `id` INT NOT NULL AUTO_INCREMENT,
    `storeDir` VARCHAR(64) NOT NULL,
    `storeName` VARCHAR(64) NOT NULL,
    `contentId` INT NOT NULL,
    `op` ENUM('UPDATE', 'DELETE') NOT NULL,
    `content` LONGTEXT NULL,
    `status` ENUM('PENDING', 'SUCCESS', 'FAILED') NOT NULL DEFAULT 'PENDING',
    `retryCount` INT NOT NULL DEFAULT 0,
    `errorMessage` TEXT NULL,
    `createdAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updatedAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_outbox_status_updatedAt` (`status`, `updatedAt`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;