import 'package:flutter/material.dart';
import '../models/address_model.dart';
import '../repositories/address_repository.dart';
import '../core/network/api_exceptions.dart';

class AddressProvider extends ChangeNotifier {
  final AddressRepository _addressRepository;
  List<AddressModel> _addresses = [];
  bool _isLoading = false;
  String? _errorMessage;

  AddressProvider({AddressRepository? addressRepository})
      : _addressRepository = addressRepository ?? AddressRepository();

  List<AddressModel> get addresses => _addresses;
  bool get isLoading => _isLoading;
  String? get errorMessage => _errorMessage;

  void clearError() {
    _errorMessage = null;
    notifyListeners();
  }

  Future<void> loadAddresses() async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      _addresses = await _addressRepository.getAddresses();
    } on ApiException catch (e) {
      _errorMessage = e.message;
    } catch (e) {
      _errorMessage = e.toString().replaceFirst('Exception: ', '');
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  Future<bool> createAddress(AddressRequest request) async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      final newAddress = await _addressRepository.createAddress(request);
      if (newAddress.isDefault) {
        _addresses = _addresses.map((a) => a.copyWith(isDefault: false)).toList();
      }
      _addresses.insert(0, newAddress);
      _isLoading = false;
      notifyListeners();
      return true;
    } on ApiException catch (e) {
      _errorMessage = e.message;
      _isLoading = false;
      notifyListeners();
      return false;
    } catch (e) {
      _errorMessage = e.toString().replaceFirst('Exception: ', '');
      _isLoading = false;
      notifyListeners();
      return false;
    }
  }

  Future<bool> updateAddress(int id, AddressRequest request) async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      final updated = await _addressRepository.updateAddress(id, request);
      if (updated.isDefault) {
        _addresses = _addresses
            .map((a) => a.id == id ? updated : a.copyWith(isDefault: false))
            .toList();
      } else {
        final index = _addresses.indexWhere((a) => a.id == id);
        if (index != -1) {
          _addresses[index] = updated;
        }
      }
      _isLoading = false;
      notifyListeners();
      return true;
    } on ApiException catch (e) {
      _errorMessage = e.message;
      _isLoading = false;
      notifyListeners();
      return false;
    } catch (e) {
      _errorMessage = e.toString().replaceFirst('Exception: ', '');
      _isLoading = false;
      notifyListeners();
      return false;
    }
  }

  Future<bool> deleteAddress(int id) async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      await _addressRepository.deleteAddress(id);
      _addresses.removeWhere((a) => a.id == id);
      _isLoading = false;
      notifyListeners();
      return true;
    } on ApiException catch (e) {
      _errorMessage = e.message;
      _isLoading = false;
      notifyListeners();
      return false;
    } catch (e) {
      _errorMessage = e.toString().replaceFirst('Exception: ', '');
      _isLoading = false;
      notifyListeners();
      return false;
    }
  }

  Future<bool> setDefaultAddress(int id) async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      final updated = await _addressRepository.setDefaultAddress(id);
      _addresses = _addresses.map((a) {
        if (a.id == id) {
          return updated;
        }
        return a.copyWith(isDefault: false);
      }).toList();
      _isLoading = false;
      notifyListeners();
      return true;
    } on ApiException catch (e) {
      _errorMessage = e.message;
      _isLoading = false;
      notifyListeners();
      return false;
    } catch (e) {
      _errorMessage = e.toString().replaceFirst('Exception: ', '');
      _isLoading = false;
      notifyListeners();
      return false;
    }
  }
}
