package com.smartrice.server.hive;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Enumeration;

/** Loaded by the driver's classloader so JDBC permits deregistering that loader's drivers. */
public final class IsolatedHiveDriverCleanup {

	private IsolatedHiveDriverCleanup() {
	}

	public static void deregister() throws SQLException {
		ClassLoader owner = IsolatedHiveDriverCleanup.class.getClassLoader();
		Enumeration<Driver> drivers = DriverManager.getDrivers();
		while (drivers.hasMoreElements()) {
			Driver driver = drivers.nextElement();
			if (driver.getClass().getClassLoader() == owner) {
				DriverManager.deregisterDriver(driver);
			}
		}
	}
}
