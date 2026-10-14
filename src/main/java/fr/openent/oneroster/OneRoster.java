package fr.openent.oneroster;

import fr.openent.oneroster.controller.AdminController;
import fr.openent.oneroster.controller.GradebookController;
import fr.openent.oneroster.controller.ResourcesController;
import fr.openent.oneroster.controller.RosteringController;
import fr.openent.oneroster.controller.TokenController;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.entcore.common.http.BaseServer;
import org.entcore.common.storage.StorageFactory;

/**
 * Module OneRoster 1.2 (1EdTech) : Open ENT fournisseur des services Rostering, Gradebook et
 * Resources (REST/JSON, OAuth 2.0 client credentials), export et import CSV OneRoster.
 */
public class OneRoster extends BaseServer {

	@Override
	public void start(final Promise<Void> startPromise) throws Exception {
		final Promise<Void> promise = Promise.promise();
		super.start(promise);
		promise.future()
				.compose(v -> StorageFactory.build(vertx, config))
				.compose(storageFactory -> initOneRoster(new OneRosterContext(vertx, vertx.eventBus(), config,
						storageFactory.getStorage())))
				.onComplete(startPromise);
	}

	private Future<Void> initOneRoster(OneRosterContext context) {
		return addController(new TokenController(context))
				.compose(v -> addController(new RosteringController(context)))
				.compose(v -> addController(new GradebookController(context)))
				.compose(v -> addController(new ResourcesController(context)))
				.compose(v -> addController(new AdminController(context)))
				.mapEmpty();
	}
}
