import 'package:flutter/material.dart';
import '../models/admin_dashboard_model.dart';
import '../models/user_model.dart';
import '../repositories/admin_repository.dart';
import '../core/network/api_exceptions.dart';

class AdminProvider extends ChangeNotifier {
  final AdminRepository _adminRepository;
  List<UserModel> _users = [];
  AdminDashboardModel? _dashboard;
  bool _isLoading = false;
  bool _isDashboardLoading = false;
  String? _errorMessage;

  String _search = '';
  String _roleFilter = 'ALL';
  bool? _statusFilter;
  int _currentPage = 0;
  int _totalPages = 0;
  int _totalElements = 0;

  AdminProvider({AdminRepository? adminRepository})
      : _adminRepository = adminRepository ?? AdminRepository();

  List<UserModel> get users => _users;
  AdminDashboardModel? get dashboard => _dashboard;
  bool get isLoading => _isLoading;
  bool get isDashboardLoading => _isDashboardLoading;
  String? get errorMessage => _errorMessage;
  String get search => _search;
  String get roleFilter => _roleFilter;
  bool? get statusFilter => _statusFilter;
  int get currentPage => _currentPage;
  int get totalPages => _totalPages;
  int get totalElements => _totalElements;

  void clearError() {
    _errorMessage = null;
    notifyListeners();
  }

  void setSearch(String query) {
    _search = query;
    _currentPage = 0;
    loadUsers();
  }

  void setRoleFilter(String role) {
    _roleFilter = role;
    _currentPage = 0;
    loadUsers();
  }

  void setStatusFilter(bool? isActive) {
    _statusFilter = isActive;
    _currentPage = 0;
    loadUsers();
  }

  void setPage(int page) {
    if (page >= 0 && page < _totalPages) {
      _currentPage = page;
      loadUsers();
    }
  }

  Future<void> loadDashboard() async {
    _isDashboardLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      _dashboard = await _adminRepository.getAdminDashboard();
    } on ApiException catch (e) {
      _errorMessage = e.message;
    } catch (e) {
      _errorMessage = e.toString().replaceFirst('Exception: ', '');
    } finally {
      _isDashboardLoading = false;
      notifyListeners();
    }
  }

  Future<void> loadUsers() async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      final response = await _adminRepository.getAllUsers(
        search: _search,
        role: _roleFilter,
        isActive: _statusFilter,
        page: _currentPage,
        size: 15,
      );
      _users = response.content;
      _totalPages = response.totalPages;
      _totalElements = response.totalElements;
      _currentPage = response.number;
    } on ApiException catch (e) {
      _errorMessage = e.message;
    } catch (e) {
      _errorMessage = e.toString().replaceFirst('Exception: ', '');
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  Future<bool> toggleUserStatus(int id, bool currentStatus) async {
    _errorMessage = null;
    notifyListeners();

    try {
      final newStatus = !currentStatus;
      final updated = await _adminRepository.updateUserStatus(id, newStatus);
      final index = _users.indexWhere((u) => u.id == id);
      if (index != -1) {
        _users[index] = updated;
      }
      notifyListeners();
      return true;
    } on ApiException catch (e) {
      _errorMessage = e.message;
      notifyListeners();
      return false;
    } catch (e) {
      _errorMessage = e.toString().replaceFirst('Exception: ', '');
      notifyListeners();
      return false;
    }
  }

  Future<bool> updateUserRole(int id, String newRole, {String? reason}) async {
    _errorMessage = null;
    notifyListeners();

    try {
      final updated = await _adminRepository.updateUserRole(id, newRole, reason: reason);
      final index = _users.indexWhere((u) => u.id == id);
      if (index != -1) {
        _users[index] = updated;
      }
      notifyListeners();
      return true;
    } on ApiException catch (e) {
      _errorMessage = e.message;
      notifyListeners();
      return false;
    } catch (e) {
      _errorMessage = e.toString().replaceFirst('Exception: ', '');
      notifyListeners();
      return false;
    }
  }
}
