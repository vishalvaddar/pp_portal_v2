const express = require('express');
const router = express.Router();
const tabController = require('../controllers/tabInventoryController');

// 1. Static Routes (MUST BE FIRST)
router.get('/tabs/stats', tabController.getTabStats); 
router.get('/tabs/eligible-students', tabController.getEligibleStudents);
router.get('/tabs/brands', tabController.getAllBrands);
router.get('/tabs/users', tabController.getAllUsers);
router.get('/tabs/cohorts', tabController.getAllCohorts); 
router.get('/tabs/movement-report', tabController.getTabMovementReport);

// 2. Collection Routes
router.get('/tabs', tabController.getAllTabs);
router.post('/tabs', tabController.createTab);
router.post('/tabs/bulk', tabController.bulkCreateTabs);
router.post('/tabs/brands', tabController.createBrand);

// 3. Dynamic Routes (MUST BE LAST)
router.get('/tabs/:tabId', tabController.getTabById);
router.put('/tabs/:tabId/status', tabController.changeTabStatus);
router.delete('/tabs/:tabId', tabController.deleteTab);
router.get('/tabs/:tabId/history', tabController.getTabHistory);

module.exports = router;