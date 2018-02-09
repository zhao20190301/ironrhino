package org.ironrhino.core.hibernate.dialect;

import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.MySQL8Dialect;
import org.hibernate.engine.jdbc.dialect.internal.StandardDialectResolver;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.ironrhino.core.jdbc.DatabaseProduct;

public class MyDialectResolver extends StandardDialectResolver {

	private static final long serialVersionUID = -3451798629900051614L;

	@Override
	public Dialect resolveDialect(DialectResolutionInfo info) {
		DatabaseProduct database = DatabaseProduct.parse(info.getDatabaseName());
		int majorVersion = info.getDatabaseMajorVersion();
		int minorVersion = info.getDatabaseMinorVersion();
		if (database == DatabaseProduct.MYSQL) {
			if (majorVersion == 8)
				return new MySQL8Dialect();
			if (majorVersion == 5 && minorVersion == 6)
				return new MySQL56Dialect();
		}
		return super.resolveDialect(info);
	}
}
